package com.mesosphere.cosmos.circe

import com.mesosphere.cosmos.http.MediaType
import com.mesosphere.cosmos.http.MediaTypeOps.mediaTypeToMediaTypeOps
import io.circe.Encoder

/** Allows an [[io.circe.Encoder]] to be selected by a [[com.mesosphere.cosmos.http.MediaType]]. */
final class DispatchingMediaTypedEncoder[A,CT<:String] private(
  private[this] val encoders: Seq[MediaTypedEncoder[A,CT]]
) {

  def apply(mediaType: MediaType): Option[MediaTypedEncoder[A,CT]] = {
    encoders.find(_.mediaType.isCompatibleWith(mediaType))
  }

  def mediaTypes: Seq[MediaType] = encoders.map(_.mediaType)

}

object DispatchingMediaTypedEncoder {

  def apply[A,CT<:String](encoders: Seq[MediaTypedEncoder[A,CT]]): DispatchingMediaTypedEncoder[A] = {
    new DispatchingMediaTypedEncoder(encoders)
  }

  def apply[A](mediaType: MediaType)(implicit
    encoder: Encoder[A]
  ): DispatchingMediaTypedEncoder[A] = {
    DispatchingMediaTypedEncoder(Seq(MediaTypedEncoder(encoder, mediaType)))
  }

}
