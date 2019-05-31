package dap4s

import monix.execution.Ack
import monix.reactive.observables.ObservableLike.Operator
import monix.reactive.{Observable, Observer}

import scala.concurrent.Future

object Aux {
  implicit final class ObserverAux[A](observer: Observer[A]) {
    def foo[B](f: B => A): Observer[B] = new Observer[B] {
      override def onNext(elem: B): Future[Ack] = observer.onNext(f(elem))
      override def onError(ex: Throwable): Unit = observer.onError(ex)
      override def onComplete(): Unit = observer.onComplete()
    }
  }

  implicit final class OperatorAux[A, B](operator: Operator[A, B]) {
    def lift(observable: Observable[A]): Observable[B] =
      observable.liftByOperator(operator)
  }
}
