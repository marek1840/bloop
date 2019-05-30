package bloop.dap

import java.net.InetSocketAddress

import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}

import scala.concurrent.Promise

final class Debuggee(task: DebugSession => Task[_])(implicit scheduler: Scheduler) {
  private val addressPromise = Promise[InetSocketAddress]()
  private var scheduled: CancelableFuture[_] = _

  def start(session: DebugSession): Unit = {
    scheduled = task(session).runAsync
  }

  def address: Task[InetSocketAddress] = Task.fromFuture(addressPromise.future)

  def bind(address: InetSocketAddress): Unit = {
    this.addressPromise.success(address)
  }

  def cancel(): Unit = {
    scheduled.cancel()
  }
}
