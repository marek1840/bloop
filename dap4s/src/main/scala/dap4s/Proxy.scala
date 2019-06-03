package dap4s

import java.util.concurrent.TimeUnit

import dap4s.DebugMessage.Response
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import monix.eval.{Callback, Task}
import monix.execution.atomic.Atomic
import monix.execution.{Ack, Scheduler}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc.{Endpoint, RequestId, Service}
import scala.util.{Failure, Success, Try}

abstract class Proxy[A](channel: Channel[A]) {
  protected def onRemoteMessage(message: A): Unit

  final def send(message: A): Future[Ack] = {
    val ack = channel.write(message)
    ack
  }

  final def listen(implicit scheduler: Scheduler): Task[Unit] = {
    val task = channel.subscribe(onRemoteMessage)
    task.runAsync
    task
  }
}

final class DebugAdapterProxy(channel: Channel[DebugMessage], services: JsonServices)(
    scheduler: Scheduler
) extends Proxy(channel) {
  private val counter = Atomic(0)
  private val requests = TrieMap.empty[RequestId, DebugMessage.Response => Unit]

  private def nextId = RequestId(counter.incrementAndGet())

  override protected def onRemoteMessage(message: DebugMessage): Unit =
    message match {
      case request: DebugMessage.Request =>
        val requestId = request.seq
        val command = request.command
        val arg = request.arguments.getOrElse(Json.Null)
        val task = Task(services.onRequest(command, arg)).map {
          case Success(result) =>
            Response.Success(nextId, requestId, command, result)
          case Failure(error) =>
            Response.Error(nextId, requestId, command, error.getMessage)
        }

        task.map(send).runAsync(scheduler)

      case response: DebugMessage.Response =>
        onResponse(response)

      case _ =>
        ???
    }

  def request[A: Encoder, B: Decoder](method: String, message: A): Task[B] = {
    // TODO notify on cancellation
    Task.create { (scheduler, callback) =>
      scheduler.scheduleOnce(FiniteDuration(0, TimeUnit.MILLISECONDS)) {
        val id = RequestId(counter.incrementAndGet())
        val json = message.asJson
        val request = DebugMessage.Request(id, method, Some(json))

        requests += (id -> (x => callback(x.to[B])))
        channel.write(request)
      }
    }
  }

  def notify(message: DebugMessage): Task[Unit] = ???

  private def onResponse(response: DebugMessage.Response): Unit = {
    requests.remove(response.request_seq) match {
      case None => ??? // TODO
      case Some(callback) =>
        callback(response)
    }
  }
}
