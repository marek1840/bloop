package dap4s

import java.nio.ByteBuffer

import dap4s.Aux._
import dap4s.DebugMessage.Response.{Error, Success}
import io.circe.derivation.JsonCodec
import io.circe.syntax._
import io.circe._
import monix.execution.{Ack, Scheduler}
import monix.reactive.observables.ObservableLike.Operator
import monix.reactive.observers.Subscriber
import monix.reactive.{Observable, Observer}

import scala.concurrent.Future
import scala.meta.jsonrpc.{BaseProtocolMessage, RequestId}
import scala.util.{Failure, Try}

abstract class DebugMessage

object DebugMessage {
  @JsonCodec final case class Request(seq: RequestId, command: String, arguments: Option[Json])
      extends DebugMessage

  sealed abstract class Response extends DebugMessage {
    def seq: RequestId
    def request_seq: RequestId
    def command: String

    def to[A: Decoder]: Try[A] =
      this match {
        case Success(_, _, _, json) =>
          json.as[A].toTry
        case Error(_, _, _, message) =>
          Failure(new IllegalStateException(message)) // TODO maybe custom exception
      }
  }

  object Response {
    @JsonCodec case class Success(
        seq: RequestId,
        request_seq: RequestId,
        command: String,
        body: Json
    ) extends Response

    @JsonCodec case class Error(
        seq: RequestId,
        request_seq: RequestId,
        command: String,
        message: String
    ) extends Response

    // TODO handle 'null' request ids
    def parseError(failure: Exception): Error =
      Error(RequestId.Null, RequestId.Null, "", failure.getMessage)

    def invalidRequest(failure: DecodingFailure): Error =
      Error(RequestId.Null, RequestId.Null, "", failure.message)

    // TODO should we send the exception message?
    def internalError(requestId: RequestId, failure: Throwable): Error =
      Error(RequestId.Null, requestId, "", failure.getMessage)
  }

  def from(json: Json): DebugMessage = {
    json.as[DebugMessage] match {
      case Left(error) =>
        Response.invalidRequest(error)
      case Right(value) =>
        value
    }
  }

  implicit val encoder: Encoder[DebugMessage] =
    msg => {
      val json = msg match {
        case r: Request => r.asJson
        case r: Response.Error => r.asJson
        case r: Response.Success => r.asJson
      }

      val t = msg match {
        case _: Request => "request"
        case _: Response => "response"
      }

      val s = msg match {
        case _: Response.Error => Some(false)
        case _: Response.Success => Some(true)
        case _ => None
      }

      val x = json.mapObject(_.add("type", t.asJson))
      s.map(value => x.mapObject(_.add("success", value.asJson))).getOrElse(x)
    }

  implicit val decoder: Decoder[DebugMessage] =
    Decoder.decodeJsonObject.emapTry { obj =>
      val json = Json.fromJsonObject(obj)

      val result = obj("type").flatMap(_.asString) match {
        case None => ??? // TODO
        case Some("request") =>
          json.as[Request]
        case Some("response") =>
          obj("success").flatMap(_.asBoolean) match {
            case None => ??? // TODO
            case Some(true) =>
              json.as[Response.Success]
            case Some(false) =>
              json.as[Response.Error]
          }
      }
      result.toTry
    }

  def channel(channel: Channel[BaseProtocolMessage]): Channel[DebugMessage] = {
    val output: Observer[DebugMessage] =
      channel.output.foo(m => BaseProtocolMessage.fromJson(m.asJson))

    val operator = new Parser(channel.output)
    val input: Observable[DebugMessage] = operator.lift(channel.input)

    Channel(input, output)
  }

  private final class Parser(output: Observer[BaseProtocolMessage])
      extends Operator[BaseProtocolMessage, DebugMessage] {

    override def apply(subscriber: Subscriber[DebugMessage]): Subscriber[BaseProtocolMessage] =
      new Subscriber[BaseProtocolMessage] {
        override def onNext(elem: BaseProtocolMessage): Future[Ack] = {
          val message = jawn.parseByteBuffer(ByteBuffer.wrap(elem.content)) match {
            case Left(error) => Response.parseError(error)
            case Right(json) => from(json)
          }
          subscriber.onNext(message)
        }

        override implicit def scheduler: Scheduler = subscriber.scheduler
        override def onError(ex: Throwable): Unit = subscriber.onError(ex)
        override def onComplete(): Unit = subscriber.onComplete()
      }
  }
}
