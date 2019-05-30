package bloop.dap

import bloop.logging.{BspClientLogger, Logger, NoopLogger}
import dap4s.{DAP, InitializeRequest, InitializeResponse}
import monix.eval.Task

import scala.meta.jsonrpc.{Response => JsonRpcResponse, Services => JsonRpcServices}

final class DebugAdapterServices(logger: Logger) {
  private type Response[A] = Task[Either[JsonRpcResponse.Error, A]]

  private val debugAdapter = new DebugAdapter

  final val services = JsonRpcServices
    .empty(new BspClientLogger(NoopLogger))
    .requestAsync(DAP.initialize)(initialize)

  private def initialize(request: InitializeRequest): Response[InitializeResponse] = {
    Task.now(Right(debugAdapter.initialize(request)))
  }
}
