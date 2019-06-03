package dap4s

import java.nio.ByteBuffer

import dap4s.Aux._
import scribe.LoggerSupport

import scala.meta.jsonrpc.{BaseProtocolMessage, BaseProtocolMessageParser, MessageWriter}

object BaseMessage {
  def channel(channel: Channel[ByteBuffer])(logger: LoggerSupport): Channel[BaseProtocolMessage] = {
    val operator = new BaseProtocolMessageParser(logger)
    val input = operator.lift(channel.input)
    val output = channel.output.foo(MessageWriter.write)

    Channel(input, output)
  }
}
