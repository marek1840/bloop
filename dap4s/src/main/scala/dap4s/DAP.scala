package dap4s

import monix.eval.Task

import scala.meta.jsonrpc._

object DAP {
  object initialize extends Endpoint[InitializeRequest, InitializeResponse]("initialize") {
    def request2(request: InitializeRequest, client: DebugAdapterProxy): Task[InitializeResponse] =
      client.request[InitializeRequest, InitializeResponse](method, request)
  }
}
