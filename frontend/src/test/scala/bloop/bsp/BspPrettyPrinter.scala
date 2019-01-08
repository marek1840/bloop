package bloop.bsp

import ch.epfl.scala.bsp
import cats.Show

object BspPrettyPrinter {
  implicit val taskStartParamsPrinter: Show[bsp.TaskStartParams] = Show.show { params =>
    val taskKind = params.dataKind.getOrElse("no kind")
    val msg = params.message.getOrElse("no message")
    val json = params.data.map(_.toString).getOrElse("No json contents")
    s"""#${params.taskId.id}: Start ${taskKind}
       |Message: ${msg}
       |Defined time? ${params.eventTime.isDefined}
       |${json}""".stripMargin
  }
}
