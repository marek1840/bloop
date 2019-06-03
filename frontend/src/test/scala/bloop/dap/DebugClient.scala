package bloop.dap

import java.net.{Socket, URI}

import dap4s._
import monix.eval.Task
import monix.execution.Scheduler
final class DebugClient(implicit client: DebugAdapterProxy) {
  def initialize(): Task[InitializeResponse] = {
    val params = new InitializeRequest
    DAP.initialize.request2(params, client)
  }

  private implicit class ResponseAdapter[A](task: Task[Either[_, A]]) {
    def orFail: Task[A] = task.map {
      case Left(error) => throw new IllegalStateException(error.toString)
      case Right(value) => value
    }
  }
}

object DebugClient {
  def apply(uri: URI)(scheduler: Scheduler): DebugClient = {
    val socket = new Socket(uri.getHost, uri.getPort)
    val channel = Channel
      .from(socket)
      .wrapWith(BaseMessage.channel(_)(null))
      .wrapWith(DebugMessage.channel)

    val services = JsonServices.empty
    implicit val proxy = new DebugAdapterProxy(channel, services)(scheduler)
    proxy.listen(scheduler)

    new DebugClient
  }
}
