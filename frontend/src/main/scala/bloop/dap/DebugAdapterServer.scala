package bloop.dap

import java.net.{Socket, URI}

import bloop.ConnectionHandle
import bloop.bsp.{BloopLanguageClient, BloopLanguageServer}
import bloop.logging.{BspClientLogger, Logger}
import monix.execution.Scheduler

import scala.meta.jsonrpc.BaseProtocolMessage

object DebugAdapterServer {
  def createAdapter()(logger: Logger, scheduler: Scheduler, ioScheduler: Scheduler): URI = {
    val server = createServer()

    ioScheduler.executeAsync { () =>
      val socket = server.serverSocket.accept
      initialize(socket)(logger, scheduler, ioScheduler)
    }

    server.uri
  }

  def initialize(
      socket: Socket
  )(logger: Logger, scheduler: Scheduler, ioScheduler: Scheduler): Unit = {
    val in = socket.getInputStream
    val out = socket.getOutputStream

    val serverLogger = new BspClientLogger(logger)
    val messages = BaseProtocolMessage.fromInputStream(in, serverLogger)

    val services = new DebugAdapterServices(serverLogger).services
    val client = new BloopLanguageClient(out, serverLogger)
    val server = new BloopLanguageServer(messages, client, services, scheduler, serverLogger)

    val task = server.startTask.runAsync(ioScheduler)
  }

  private def createServer(): ConnectionHandle = {
    ConnectionHandle.tcp(backlog = 10) // TODO tune backlog?
  }
}
