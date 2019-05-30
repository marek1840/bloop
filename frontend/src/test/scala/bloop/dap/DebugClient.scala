package bloop.dap

import java.net.{Socket, URI}

import bloop.bsp.{BloopLanguageClient, BloopLanguageServer}
import bloop.logging.{BspClientLogger, NoopLogger}
import dap4s.{DAP, InitializeRequest, InitializeResponse}
import monix.eval.Task
import monix.execution.Scheduler

import scala.meta.jsonrpc.{
  BaseProtocolMessage,
  JsonRpcClient,
  MessageWriter,
  Response => JsonRpcResponse,
  Services => JsonRpcServices
}
final class DebugClient(implicit client: JsonRpcClient) {
  def initialize(): Task[InitializeResponse] = {
    val params = new InitializeRequest
    DAP.initialize.request(params).orFail
  }

  private implicit class ResponseAdapter[A](task: Task[Either[_, A]]) {
    def orFail: Task[A] = task.map {
      case Left(error) => throw new IllegalStateException(error.toString)
      case Right(value) => value
    }
  }
}

object DebugClient {
  def apply(uri: URI)(implicit scheduler: Scheduler): DebugClient = {
    val socket = new Socket(uri.getHost, uri.getPort)
    val in = socket.getInputStream
    val out = socket.getOutputStream

    val logger = new BspClientLogger(NoopLogger)
    val messages = BaseProtocolMessage.fromInputStream(in, logger)
    implicit val client: BloopLanguageClient = new BloopLanguageClient(out, logger)

    val services = JsonRpcServices.empty(new BspClientLogger(NoopLogger))
    val server = new BloopLanguageServer(messages, client, services, scheduler, logger)
    server.startTask.runAsync(scheduler)

    new DebugClient
  }
}
