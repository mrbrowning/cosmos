package com.mesosphere.cosmos

import com.mesosphere.cosmos.http.RequestSession
import com.mesosphere.cosmos.thirdparty.mesos.master.model.{MasterState, MesosFrameworkTearDownResponse}
import com.mesosphere.cosmos.thirdparty.mesos.master.circe.Decoders._
import com.netaporter.uri.Uri
import com.netaporter.uri.dsl._
import com.twitter.finagle.Service
import com.twitter.finagle.http._
import com.twitter.util.Future
import org.jboss.netty.handler.codec.http.HttpMethod

class MesosMasterClient(
  mesosUri: Uri,
  client: Service[Request, Response]
) extends ServiceClient(mesosUri) {

  def tearDownFramework(frameworkId: String)(implicit session: RequestSession): Future[MesosFrameworkTearDownResponse] = {
    val formData = Uri.empty.addParam("frameworkId", frameworkId)
    // scala-uri makes it convenient to encode the actual framework id, but it will think its for a Uri
    // so we strip the leading '?' that signifies the start of a query string
    val encodedString = formData.toString.substring(1)
    val uri = "master" / "teardown"
    client(postForm(uri, encodedString))
      .map(validateResponseStatus(HttpMethod.POST, uri, _))
      .map { _ => MesosFrameworkTearDownResponse() }
  }

  def getMasterState(frameworkName: String)(implicit session: RequestSession): Future[MasterState] = {
    val uri = "master" / "state"
    val request = get(uri)
    client(request).flatMap(decodeTo[MasterState](HttpMethod.GET, uri, _))
  }

}
