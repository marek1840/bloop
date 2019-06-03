package dap4s

import java.io.{InputStream, OutputStream}
import java.net.Socket
import java.nio.ByteBuffer

import monix.eval.Task
import monix.execution.Ack
import monix.reactive.{Observable, Observer}

import scala.concurrent.Future

final case class Channel[A](input: Observable[A], output: Observer[A]) {
  def wrapWith[B](f: Channel[A] => Channel[B]): Channel[B] = f(this)

  def subscribe(f: A => Unit): Task[Unit] = input.foreachL(f)
  def write(value: A): Future[Ack] = output.onNext(value)
}

object Channel {
  def raw(in: InputStream, out: OutputStream): Channel[ByteBuffer] = {
    val input = Observable.fromInputStream(in).map(ByteBuffer.wrap)
    val output = new OutputStreamObserver(out)
    Channel(input, output)
  }

  def from(socket: Socket): Channel[ByteBuffer] =
    raw(socket.getInputStream, socket.getOutputStream)

  private final class OutputStreamObserver(out: OutputStream) extends Observer.Sync[ByteBuffer] {
    private[this] var isClosed: Boolean = false
    override def onNext(elem: ByteBuffer): Ack = out.synchronized {
      if (isClosed) Ack.Stop
      else {
        try {
          while (elem.hasRemaining) out.write(elem.get().toInt)
          out.flush()
          Ack.Continue
        } catch {
          case _: java.io.IOException =>
            isClosed = true
            Ack.Stop
        }
      }
    }
    override def onError(ex: Throwable): Unit = ()
    override def onComplete(): Unit = {
      out.synchronized {
        out.close()
      }
    }
  }
}
