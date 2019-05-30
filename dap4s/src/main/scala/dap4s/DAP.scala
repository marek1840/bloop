package dap4s

import scala.meta.jsonrpc.Endpoint

object DAP {
  object initialize extends Endpoint[InitializeRequest, InitializeResponse]("initialize")
}
