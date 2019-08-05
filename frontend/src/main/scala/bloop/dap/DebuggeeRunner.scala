package bloop.dap

import bloop.data.{Platform, Project}
import bloop.engine.State
import bloop.engine.tasks.{RunMode, Tasks}
import bloop.exec.JavaEnv
import bloop.testing.{LoggingEventHandler, TestInternals, TestSuiteEventHandler}
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task

trait DebuggeeRunner {
  def run(logger: DebugSessionLogger): Task[Unit]
}

private final class MainClassDebugAdapter(
    project: Project,
    mainClass: ScalaMainClass,
    env: JavaEnv,
    recompile: Task[State]
) extends DebuggeeRunner {
  def run(debugLogger: DebugSessionLogger): Task[Unit] = {
    val task = recompile.flatMap { state =>
      val workingDir = state.commonOptions.workingPath
      Tasks.runJVM(
        state.copy(logger = debugLogger),
        project,
        env,
        workingDir,
        mainClass.`class`,
        mainClass.arguments.toArray,
        skipJargs = false,
        RunMode.Debug
      )
    }

    task.map(_ => ())
  }
}

private final class TestSuiteDebugAdapter(
    projects: Seq[Project],
    filters: List[String],
    recompile: Task[State]
) extends DebuggeeRunner {
  def run(debugLogger: DebugSessionLogger): Task[Unit] = {
    val task = recompile.flatMap { state =>
      val debugState = state.copy(logger = debugLogger)

      val filter = TestInternals.parseFilters(filters)
      val handler = new LoggingEventHandler(debugState.logger)

      Tasks.test(
        debugState,
        projects.toList,
        Nil,
        filter,
        handler,
        failIfNoTestFrameworks = true,
        runInParallel = false,
        mode = RunMode.Debug
      )
    }

    task.map(_ => ())
  }
}

object DebuggeeRunner {
  def forMainClass(projects: Seq[Project], recompile: Task[State])(
      mainClass: ScalaMainClass
  ): Either[String, DebuggeeRunner] = {
    projects match {
      case Seq() => Left(s"No projects specified for main class: [$mainClass]")
      case Seq(project) =>
        project.platform match {
          case jvm: Platform.Jvm =>
            Right(new MainClassDebugAdapter(project, mainClass, jvm.env, recompile))
          case platform =>
            Left(s"Unsupported platform: ${platform.getClass.getSimpleName}")
        }

      case projects => Left(s"Multiple projects specified for main class [$mainClass]: $projects")
    }
  }

  def forTestSuite(projects: Seq[Project], recompile: Task[State])(
      filters: List[String]
  ): Either[String, DebuggeeRunner] = {
    projects match {
      case Seq() => Left(s"No projects specified for the test suites: [${filters.sorted}]")
      case projects => Right(new TestSuiteDebugAdapter(projects, filters, recompile))
    }
  }
}
