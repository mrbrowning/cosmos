package com.mesosphere.cosmos.repository

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import cats.data.Ior
import com.netaporter.uri.Uri
import com.twitter.finagle.stats.{NullStatsReceiver, Stat, StatsReceiver}
import com.twitter.util._
import io.circe.Encoder
import io.circe.parse._
import io.circe.syntax._
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.BackgroundCallback
import org.apache.curator.framework.api.CuratorEvent
import org.apache.curator.framework.api.CuratorEventType
import org.apache.curator.framework.recipes.cache.NodeCache
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.data.{Stat => ZooKeeperStat}

import com.mesosphere.cosmos._
import com.mesosphere.cosmos.circe.Decoders._
import com.mesosphere.cosmos.circe.Encoders._
import com.mesosphere.cosmos.http.{MediaType, MediaTypeOps, MediaTypeSubType}
import com.mesosphere.cosmos.model.{PackageRepository, ZooKeeperStorageEnvelope}

private[cosmos] final class ZooKeeperStorage(
  zkClient: CuratorFramework,
  defaultUniverseUri: Uri
)(implicit
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends PackageSourcesStorage {

  import ZooKeeperStorage._

  private[this] val caching = new NodeCache(zkClient, ZooKeeperStorage.PackageRepositoriesPath)
  caching.start()

  private[this] val stats = statsReceiver.scope("zkStorage")

  private[this] val envelopeMediaType = MediaType(
    "application",
    MediaTypeSubType("vnd.dcos.package.repository.repo-list", Some("json")),
    Some(Map(
      "charset" -> "utf-8",
      "version" -> "v1"
    ))
  )

  private[this] val DefaultSources: List[PackageRepository] =
    List(PackageRepository("Universe", defaultUniverseUri))

  override def read(): Future[List[PackageRepository]] = {
    Stat.timeFuture(stats.stat("read")) {
      readFromZooKeeper.flatMap {
        case Some((_, bytes)) =>
          Future(decodeData(bytes))
        case None =>
          create(DefaultSources)
      }
    }
  }

  override def readCache(): Future[List[PackageRepository]] = {
    val readCacheStats = stats.scope("readCache")
    Stat.timeFuture(readCacheStats.stat("call")) {
      readFromCache.flatMap {
        case Some((_, bytes)) =>
          readCacheStats.counter("hit").incr
          Future(decodeData(bytes))
        case None =>
          readCacheStats.counter("miss").incr
          create(DefaultSources)
      }
    }
  }


  override def add(
    index: Option[Int],
    packageRepository: PackageRepository
  ): Future[List[PackageRepository]] = {
    Stat.timeFuture(stats.stat("add")) {
      readFromZooKeeper.flatMap {
        case Some((stat, bytes)) =>
          write(stat, addToList(index, packageRepository, decodeData(bytes)))

        case None =>
          create(addToList(index, packageRepository, DefaultSources))
      }
    }
  }

  override def delete(name: Option[String], uri: Option[Uri]): Future[List[PackageRepository]] = {
    Future.value(getPredicate(name, uri)).lowerFromTry.flatMap { predicate =>
      Stat.timeFuture(stats.stat("delete")) {
        readFromZooKeeper.flatMap {
          case Some((stat, bytes)) =>
            write(stat, decodeData(bytes).filterNot(predicate))
          case None =>
            create(DefaultSources.filterNot(predicate))
        }
      }
    }
  }

  private[this] def create(
    repositories: List[PackageRepository]
  ): Future[List[PackageRepository]] = {
    val promise = Promise[List[PackageRepository]]()

    zkClient.create.creatingParentsIfNeeded.inBackground(
      new CreateHandler(promise, repositories)
    ).forPath(
      ZooKeeperStorage.PackageRepositoriesPath,
      encodeEnvelope(toByteBuffer(repositories))
    )

    promise
  }

  private[this] def write(
    stat: ZooKeeperStat,
    repositories: List[PackageRepository]
  ): Future[List[PackageRepository]] = {
    val promise = Promise[List[PackageRepository]]()

    zkClient.setData().withVersion(stat.getVersion).inBackground(
      new WriteHandler(promise, repositories)
    ).forPath(
      ZooKeeperStorage.PackageRepositoriesPath,
      encodeEnvelope(toByteBuffer(repositories))
    )

    promise
  }

  private[this] def readFromCache: Future[Option[(ZooKeeperStat, Array[Byte])]] = {
    Future {
      Option(caching.getCurrentData()).map { data =>
        (data.getStat, data.getData)
      }
    }
  }

  private[this] def readFromZooKeeper: Future[Option[(ZooKeeperStat, Array[Byte])]] = {
    val promise = Promise[Option[(ZooKeeperStat, Array[Byte])]]()

    zkClient.getData().inBackground(
      new ReadHandler(promise)
    ).forPath(
      ZooKeeperStorage.PackageRepositoriesPath
    )

    promise
  }

  private[this] def addToList(
    index: Option[Int],
    elem: PackageRepository,
    list: List[PackageRepository]
  ): List[PackageRepository] = {
    val duplicateName = list.find(_.name == elem.name).map(_.name)
    val duplicateUri = list.find(_.uri == elem.uri).map(_.uri)
    val duplicates = (duplicateName, duplicateUri) match {
      case (Some(n), Some(u)) => Some(Ior.Both(n, u))
      case (Some(n), _) => Some(Ior.Left(n))
      case (_, Some(u)) => Some(Ior.Right(u))
      case _ => None
    }
    duplicates.foreach(d => throw new RepositoryAlreadyPresent(d))

    index match {
      case Some(i) if 0 <= i && i < list.size =>
        val (leftSources, rightSources) = list.splitAt(i)
        leftSources ++ (elem :: rightSources)
      case Some(i) if i == list.size =>
        list :+ elem
      case None =>
        list :+ elem
      case Some(i) =>
        throw new RepositoryAddIndexOutOfBounds(i, list.size - 1)
    }
  }

  private[this] def decodeData(bytes: Array[Byte]): List[PackageRepository] = {
    decode[ZooKeeperStorageEnvelope](new String(bytes, StandardCharsets.UTF_8))
      .flatMap { envelope =>
        val contentType = envelope.metadata
          .get("Content-Type")
          .flatMap { s => MediaType.parse(s).toOption }

        contentType match {
          case Some(mt) if MediaTypeOps.compatible(envelopeMediaType, mt) =>
            val dataString: String = new String(
              ByteBuffers.getBytes(envelope.data),
              StandardCharsets.UTF_8)
            decode[List[PackageRepository]](dataString)
          case Some(mt) =>
            throw new ZooKeeperStorageError(
              s"Error while trying to deserialize data. " +
              s"Expected Content-Type '${envelopeMediaType.show}' actual '${mt.show}'"
            )
          case None =>
            throw new ZooKeeperStorageError(
              s"Error while trying to deserialize data. " +
              s"Content-Type not defined."
            )
        }
      } valueOr { err => throw new CirceError(err) }
  }

  private[this] def toByteBuffer[A : Encoder](a: A): ByteBuffer = {
    ByteBuffer.wrap(a.asJson.noSpaces.getBytes(StandardCharsets.UTF_8))
  }

  private[this] def encodeEnvelope(data: ByteBuffer): Array[Byte] = {
    ZooKeeperStorageEnvelope(
      metadata = Map("Content-Type" -> envelopeMediaType.show),
      data = data
    ).asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
  }
}

private object ZooKeeperStorage {
  private val PackageRepositoriesPath: String = "/package/repositories"

  private[cosmos] def getPredicate(name: Option[String], uri: Option[Uri]): Try[(PackageRepository) => Boolean] = {
    def namePredicate(n: String) = (repo: PackageRepository) => repo.name == n
    def uriPredicate(u: Uri) = (repo: PackageRepository) => repo.uri == u

    (name, uri) match {
      case (Some(n), Some(u)) =>
        Return((repo: PackageRepository) => namePredicate(n)(repo) && uriPredicate(u)(repo))
      case (Some(n), None) =>
        Return(namePredicate(n))
      case (None, Some(u)) =>
        Return(uriPredicate(u))
      case (None, None) =>
        Throw(new RepoNameOrUriMissing())
    }
  }
}

private final class WriteHandler(
  promise: Promise[List[PackageRepository]],
  repositories: List[PackageRepository]
) extends  BackgroundCallback {
  private[this] val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  override def processResult(client: CuratorFramework, event: CuratorEvent): Unit ={
    if (event.getType == CuratorEventType.SET_DATA) {
      val code = KeeperException.Code.get(event.getResultCode)
      if (code == KeeperException.Code.OK) {
        promise.setValue(repositories)
      } else {
        val exception = if (code == KeeperException.Code.BADVERSION) {
          // BADVERSION is expected so let's deplay a friendlier error
          ConcurrentAccess(KeeperException.create(code, event.getPath))
        } else {
          KeeperException.create(code, event.getPath)
        }

        promise.setException(exception)
      }
    } else {
      logger.error("Repository storage write callback called for incorrect event: {}", event)
    }
  }
}

private final class CreateHandler(
  promise: Promise[List[PackageRepository]],
  repositories: List[PackageRepository]
) extends  BackgroundCallback {
  private[this] val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  override def processResult(client: CuratorFramework, event: CuratorEvent): Unit ={
    if (event.getType == CuratorEventType.CREATE) {
      val code = KeeperException.Code.get(event.getResultCode)
      if (code == KeeperException.Code.OK) {
        promise.setValue(repositories)
      } else {
        val exception = if (code == KeeperException.Code.NODEEXISTS) {
          // NODEEXISTS is expected so let's deplay a friendlier error
          ConcurrentAccess(KeeperException.create(code, event.getPath))
        } else {
          KeeperException.create(code, event.getPath)
        }

        promise.setException(exception)
      }
    } else {
      logger.error("Repository storage create callback called for incorrect event: {}", event)
    }
  }
}

private final class ReadHandler(
  promise: Promise[Option[(ZooKeeperStat, Array[Byte])]]
) extends  BackgroundCallback {
  private[this] val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  override def processResult(client: CuratorFramework, event: CuratorEvent): Unit ={
    if (event.getType == CuratorEventType.GET_DATA) {
      val code = KeeperException.Code.get(event.getResultCode)
      if (code == KeeperException.Code.OK) {
        promise.setValue(Some((event.getStat, event.getData)))
      } else if (code == KeeperException.Code.NONODE) {
        promise.setValue(None)
      } else {
        promise.setException(KeeperException.create(code, event.getPath))
      }
    } else {
      logger.error("Repository storage read callback called for incorrect event: {}", event)
    }
  }
}
