package bloop.dap

import java.net.{Socket, URI}

import bloop.ConnectionHandle
import bloop.logging.Logger
import dap4s._
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.Try

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
    val channel = Channel
      .from(socket)
      .wrapWith(BaseMessage.channel(_)(null))
      .wrapWith(DebugMessage.channel)

    val service = JsonServices.empty
      .request(DAP.initialize)(_ => Try(new InitializeResponse))

    val proxy = new DebugAdapterProxy(channel, service)(scheduler)
    proxy.listen(ioScheduler)
  }

  private def createServer(): ConnectionHandle = {
    ConnectionHandle.tcp(backlog = 10) // TODO tune backlog?
  }
}
