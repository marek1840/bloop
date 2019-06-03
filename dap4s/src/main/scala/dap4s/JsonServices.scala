package dap4s

import dap4s.JsonServices.Adapter
import io.circe._
import io.circe.syntax._

import scala.meta.jsonrpc.Endpoint
import scala.util.{Failure, Try}

final class JsonServices(services: Map[String, Adapter[_, _]]) {
  def onRequest(endpoint: String, json: Json): Try[Json] = {
    services.get(endpoint) match {
      case Some(service) =>
        service(json)
      case None =>
        Failure(new IllegalStateException(s"Unknown endpoint: $endpoint"))
    }
  }

  // TODO extract builder when more methods come?
  def request[A: Decoder, B: Encoder](endpoint: Endpoint[A, B])(f: A => Try[B]): JsonServices = {
    val services = this.services + (endpoint.method -> new Adapter(f))
    new JsonServices(services)
  }
}

object JsonServices {
  val empty = new JsonServices(Map.empty)

  private final class Adapter[A: Decoder, B: Encoder](f: A => Try[B]) extends (Json => Try[Json]) {
    override def apply(json: Json): Try[Json] = {
      for {
        input <- json.as[A].toTry
        result <- f(input)
      } yield result.asJson
    }
  }
}
