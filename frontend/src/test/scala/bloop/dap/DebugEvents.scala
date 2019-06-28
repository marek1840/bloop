package bloop.dap

import bloop.dap.DebugEvents.Channel
import com.microsoft.java.debug.core.protocol.Messages
import monix.eval.Task
import monix.execution.Ack
import monix.reactive.Observer

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Promise

private[dap] final class DebugEvents extends Observer[Messages.Event] {
  private val channels = TrieMap.empty[String, Channel]
  private var completed = false

  def channel(name: String): Channel =
    channels.getOrElseUpdate(name, newChannel)

  override def onNext(elem: Messages.Event): Ack = {
    channel(elem.event).onNext(elem)
    Ack.Continue
  }

  override def onError(ex: Throwable): Unit =
    completed.synchronized {
      channels.values.foreach(channel => channel.onError(ex))
      completed = true
    }

  override def onComplete(): Unit =
    completed.synchronized {
      channels.values.foreach(channel => channel.onComplete())
      completed = true
    }

  private def newChannel: Channel =
    completed.synchronized {
      val channel = new Channel
      if (completed) channel.onComplete()
      channel
    }
}

object DebugEvents {
  class Channel {
    private val buffer = mutable.Buffer.empty[Messages.Event]
    private val firstEvent = Promise[Unit]()
    private val completed = Promise[Unit]()

    def first: Task[Messages.Event] = {
      Task
        .fromFuture(firstEvent.future)
        .map(_ => buffer.head)
    }

    def all: Task[Seq[Messages.Event]] = {
      Task
        .fromFuture(completed.future)
        .map(_ => buffer)
    }

    private[dap] def onNext(event: Messages.Event): Unit = {
      buffer.append(event)
      if (!firstEvent.isCompleted) firstEvent.success(event)
    }

    private[dap] def onError(ex: Throwable): Unit = {
      firstEvent.tryFailure(ex)
      completed.tryFailure(ex)
    }

    private[dap] def onComplete(): Unit = {
      firstEvent.tryFailure(new NoSuchElementException) // if no events were published
      completed.success(())
    }
  }
}
