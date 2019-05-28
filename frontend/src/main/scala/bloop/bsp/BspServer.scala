package bloop.bsp

import java.net.Socket
import java.net.ServerSocket
import java.util.Locale

import bloop.ConnectionHandle
import bloop.cli.Commands
import bloop.data.ClientInfo
import bloop.engine.{ExecutionContext, State}
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspClientLogger, DebugFilter}
import bloop.sockets.UnixDomainServerSocket
import bloop.sockets.Win32NamedPipeServerSocket
import monix.eval.Task
import monix.execution.Ack
import monix.execution.Scheduler
import monix.execution.Cancelable
import monix.execution.atomic.Atomic
import monix.execution.misc.NonFatal
import monix.reactive.OverflowStrategy
import monix.reactive.observers.Subscriber
import monix.reactive.{Observable, Observer}
import monix.reactive.observables.ObservableLike
import monix.execution.cancelables.CompositeCancelable

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.jsonrpc.{BaseProtocolMessage, LanguageClient, LanguageServer}

object BspServer {
  private implicit val logContext: DebugFilter = DebugFilter.Bsp

  import Commands.ValidatedBsp
  private def initServer(cmd: ValidatedBsp, state: State): Task[ConnectionHandle] = {
    val handle = cmd match {
      case Commands.WindowsLocalBsp(pipeName, _) =>
        ConnectionHandle.windows(pipeName)
      case Commands.UnixLocalBsp(socketFile, _) =>
        ConnectionHandle.unix(socketFile)
      case Commands.TcpBsp(address, portNumber, _) =>
        ConnectionHandle.tcp(address, portNumber, backlog = 10)
    }
    state.logger.debug(s"Waiting for a connection at $handle...")
    Task(handle).doOnCancel(Task(handle.close()))
  }

  def run(
      cmd: ValidatedBsp,
      state: State,
      config: RelativePath,
      promiseWhenStarted: Option[Promise[Unit]],
      externalObserver: Option[Observer.Sync[State]],
      scheduler: Scheduler,
      ioScheduler: Scheduler
  ): Task[State] = {
    import state.logger

    def startServer(handle: ConnectionHandle): Task[State] = {
      val connectionURI = handle.uri

      // Do NOT change this log, it's used by clients to know when to start a connection
      logger.info(s"The server is listening for incoming connections at $connectionURI...")
      promiseWhenStarted.foreach(_.success(()))

      val socket = handle.serverSocket.accept()
      logger.info(s"Accepted incoming BSP client connection at $connectionURI")

      val status = Atomic(0)
      val in = socket.getInputStream
      val out = socket.getOutputStream

      // FORMAT: OFF
      val bspLogger = new BspClientLogger(logger)
      val client = new BloopLanguageClient(out, bspLogger)
      val messages = BaseProtocolMessage.fromInputStream(in, bspLogger)
      val provider = new BloopBspServices(state, client, config, in, status, externalObserver, scheduler, ioScheduler)
      val server = new BloopLanguageServer(messages, client, provider.services, ioScheduler, bspLogger)
      // FORMAT: ON

      def error(msg: String): Unit = provider.stateAfterExecution.logger.error(msg)

      /* This implementation of starting a server relies on two observables:
       *
       *   1. An observable with a publish strategy that gets protocol messages
       *      and forwards them to the bloop server and services implementation.
       *   2. An observable that pumps input from the socket `InputStream`,
       *      parses it into BSP messages and forwards it to the previous
       *      observable.
       *
       * We use two observables instead of one because if the client crashes or
       * disconnects, we want to cancel all tasks triggered by the first
       * observable as soon as possible. If we were using only one observable,
       * we would not receive RST or FIN socket messages because the next
       * `read` call would not happen until the spawn server tasks are
       * finished. In our case, as soon as we have parsed a successful message,
       * we will call `read` and wait on a read result, EOF or a connection
       * reset/IO exception.
       */

      import monix.reactive.Observable
      import monix.reactive.MulticastStrategy
      val (bufferedObserver, endObservable) =
        Observable.multicast(MulticastStrategy.publish[BaseProtocolMessage])(ioScheduler)

      import scala.collection.mutable
      import monix.execution.cancelables.AssignableCancelable
      // We set the value of this cancelable when we start consuming task
      var completeSubscribers: Cancelable = Cancelable.empty
      val cancelables = new mutable.ListBuffer[Cancelable]()
      val cancelable = AssignableCancelable.multi { () =>
        val tasksToCancel = cancelables.synchronized { cancelables.toList }
        Cancelable.cancelAll(completeSubscribers :: tasksToCancel)
      }

      val isCommunicationActive = Atomic(true)
      def onFinishOrCancel[T](cancelled: Boolean, result: Option[Throwable]) = Task {
        if (isCommunicationActive.getAndSet(false)) {
          if (cancelled) error(s"BSP server cancelled, closing socket...")
          else result.foreach(t => error(s"BSP server stopped by ${t.getMessage}"))
          cancelable.cancel()
          server.cancelAllRequests()
          val latestState = provider.stateAfterExecution
          closeCommunication(externalObserver, latestState, socket, handle.serverSocket)
        }
      }

      import monix.reactive.Consumer
      val singleThreadedConsumer = Consumer.foreachAsync[BaseProtocolMessage] { msg =>
        import scala.meta.jsonrpc.Response.Empty
        import scala.meta.jsonrpc.Response.Success
        val taskToRun = {
          server
            .handleMessage(msg)
            .flatMap(msg => Task.fromFuture(client.serverRespond(msg)).map(_ => ()))
            .onErrorRecover { case NonFatal(e) => bspLogger.error("Unhandled error", e); () }
        }

        val cancelable = taskToRun.runAsync(ioScheduler)
        cancelables.synchronized { cancelables.+=(cancelable) }
        Task
          .fromFuture(cancelable)
          .doOnFinish(_ => Task { cancelables.synchronized { cancelables.-=(cancelable) }; () })
      }

      val startedSubscription: Promise[Unit] = Promise[Unit]()

      /**
       * Make manual subscription to consumer so that we can control the
       * cancellation for both the source and the consumer. Otherwise, there is
       * no way to call the cancelable produced by the consumer.
       */
      val consumingWithBalancedForeach = Task.create[List[Unit]] { (scheduler, cb) =>
        val parallelConsumer = Consumer.loadBalance(4, singleThreadedConsumer)
        val (out, consumerSubscription) = parallelConsumer.createSubscriber(cb, scheduler)
        val cancelOut = Cancelable(() => out.onComplete())
        completeSubscribers = CompositeCancelable(cancelOut)
        val sourceSubscription = endObservable.subscribe(out)
        startedSubscription.success(())
        CompositeCancelable(sourceSubscription, consumerSubscription)
      }

      val consumingTask = consumingWithBalancedForeach
        .doOnCancel(onFinishOrCancel(true, None))
        .doOnFinish(result => onFinishOrCancel(false, result))
        .flatMap(_ => server.awaitRunningTasks.map(_ => provider.stateAfterExecution))
      val consumerFuture = consumingTask.runAsync(ioScheduler)

      /*
       * Cancel any ongoing tasks if the socket `InputStream` completed but the
       * provider did not receive the appropriate shutdown mechanism.
       *
       * This can happen when clients suddently crash or exit with the Unix
       * domain and Windows named pipe implementation, because in these
       * implementations there are no special protocol messages to signal
       * closing, unlike TCP that has `FIN` and `RST`. Unfortunately with
       * Domain Socket and Windows named pipes, `socket.isClosed()` will always
       * be false from the server side because of this reason.
       */
      val cancelIfAwaitingExit: Task[Unit] = Task {
        if (!provider.exited.get) {
          consumerFuture.cancel()
        }
      }

      val startListeningToMessages = messages
        .liftByOperator(new PumpOperator(bufferedObserver, consumerFuture))
        .completedL
        .doOnFinish(_ => cancelIfAwaitingExit)
        .flatMap { _ =>
          Task.fromFuture(consumerFuture).map { latestState =>
            // Complete the states observable to signal exit on consumers
            externalObserver.foreach(_.onComplete())
            // Return the latest state
            latestState
          }
        }

      // Make sure we only start listening when the subscription has started,
      // there is a race condition and we might miss the initialization messages
      for {
        _ <- Task.fromFuture(startedSubscription.future).executeOn(ioScheduler)
        latestState <- startListeningToMessages.executeOn(ioScheduler)
      } yield latestState
    }

    initServer(cmd, state).materialize.flatMap {
      case scala.util.Success(handle: ConnectionHandle) =>
        startServer(handle).onErrorRecoverWith {
          case t => Task.now(state.withError(s"Exiting BSP server with ${t.getMessage}", t))
        }
      case scala.util.Failure(t: Throwable) =>
        promiseWhenStarted.foreach(p => if (!p.isCompleted) p.failure(t))
        Task.now(state.withError(s"BSP server failed to open a socket: '${t.getMessage}'", t))
    }
  }

  def closeCommunication(
      externalObserver: Option[Observer[State]],
      latestState: State,
      socket: Socket,
      serverSocket: ServerSocket
  ): Unit = {
    val deleteExternalDirsTask = latestState.build.projects.map { project =>
      import bloop.io.Paths
      import java.io.IOException
      val externalClientClassesDir = latestState.client.getUniqueClassesDirFor(project)
      if (externalClientClassesDir == project.genericClassesDir) Task.now(())
      else Task.eval(Paths.delete(externalClientClassesDir)).executeWithFork
    }

    // Run deletion of client external classes directories in IO pool
    val groups = deleteExternalDirsTask.grouped(4).map(group => Task.gatherUnordered(group))
    Task
      .sequence(groups)
      .map(_.flatten)
      .materialize
      .map(_ => ())
      .runAsync(ExecutionContext.ioScheduler)

    // Close any socket communication asap
    try socket.close()
    finally serverSocket.close()
  }

  final class PumpOperator[A](pumpTarget: Observer.Sync[A], runningFuture: Cancelable)
      extends ObservableLike.Operator[A, A] {
    def apply(out: Subscriber[A]): Subscriber[A] =
      new Subscriber[A] { self =>
        implicit val scheduler = out.scheduler
        private[this] val isActive = Atomic(true)

        def onNext(elem: A): Future[Ack] =
          out.onNext(elem).syncOnContinue {
            // Forward and ignore ack; safe because observer is sync
            pumpTarget.onNext(elem)
            ()
          }

        def onComplete(): Unit = {
          if (isActive.getAndSet(false))
            out.onComplete()
        }

        def onError(ex: Throwable): Unit = {
          if (isActive.getAndSet(false)) {
            // Complete instead of forwarding error so that completeL finishes
            out.onComplete()
            runningFuture.cancel()
          } else {
            scheduler.reportFailure(ex)
          }
        }
      }
  }
}
