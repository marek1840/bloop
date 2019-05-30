package bloop.dap

import dap4s.{InitializeRequest, InitializeResponse}

final class DebugAdapter {
  def initialize(params: InitializeRequest): InitializeResponse = {
    new InitializeResponse
  }
}
