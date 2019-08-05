package bloop.dap

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.cli.BspProtocol
import bloop.dap.DebugTestProtocol.Response.{Failure, Success}
import bloop.logging.RecordingLogger
import bloop.util.{TestProject, TestUtil}
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.LaunchParametersDataKind._
import monix.eval.Task

object DebugProtocolSpec extends DebugBspBaseSuite {
  override val protocol: BspProtocol = BspProtocol.Local

  test("starts a debug session") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello, World!")
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val output = state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            _ <- client.disconnect()
            output <- client.allOutput
          } yield output
        }

        assertNoDiff(output, "Hello, World!\n")
      }
    }
  }

  // when the session detaches from the JVM, the JDI once again writes to the standard output
  test("restarted session does not contain JDI output") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    Thread.sleep(10000)
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val output = state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()

            previousSession <- client.restart()

            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.disconnect()

            previousSessionOutput <- previousSession.allOutput
          } yield previousSessionOutput
        }

        assertNoDiff(output, "")
      }
    }
  }

  test("picks up source changes across sessions") {
    val changedVersion =
      """object Main {
        |  def main(args: Array[String]): Unit = {
        |    println("Non-blocking Hello!")
        |  }
        |}
     """.stripMargin

    TestUtil.withinWorkspace { workspace =>
      val originalVersion =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Blocking Hello!")
           |    synchronized(wait())
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(originalVersion))

      loadBspState(workspace, List(project), logger) { state =>
        // start debug session and the immediately disconnect from it
        val blockingSessionOutput = state.withDebugSession(project, mainClassParams("Main")) {
          client =>
            for {
              _ <- client.initialize()
              _ <- client.launch()
              _ <- client.configurationDone()
              output <- client.firstOutput
              _ <- client.disconnect()
            } yield output
        }

        assertNoDiff(blockingSessionOutput, "Blocking Hello!")

        // fix the main class
        val sources = state.toTestState.getProjectFor(project).sources
        val mainFile = sources.head.resolve("main/scala/Main.scala")
        Files.write(mainFile.underlying, changedVersion.getBytes(StandardCharsets.UTF_8))

        // start the next debug session
        val output = state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            _ <- client.disconnect()
            output <- client.allOutput
          } yield output
        }

        assertNoDiff(output, "Non-blocking Hello!")
      }
    }
  }

  test("picks up source changes across restarts") {
    val changedVersion =
      """object Main {
        |  def main(args: Array[String]): Unit = {
        |    println("Non-blocking Hello!")
        |  }
        |}
     """.stripMargin

    TestUtil.withinWorkspace { workspace =>
      val originalVersion =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Blocking Hello!")
           |    synchronized(wait())
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(originalVersion))

      loadBspState(workspace, List(project), logger) { state =>
        // start debug session and the immediately disconnect from it
        state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            originalOutput <- client.firstOutput
            _ <- Task {
              // fix the main class
              val sources = state.toTestState.getProjectFor(project).sources
              val mainFile = sources.head.resolve("main/scala/Main.scala")
              Files.write(mainFile.underlying, changedVersion.getBytes(StandardCharsets.UTF_8))
            }
            _ <- client.restart()
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            _ <- client.disconnect()
            changedOutput <- client.allOutput
          } yield {
            assertNoDiff(originalOutput, "Blocking Hello!")
            assertNoDiff(changedOutput, "Non-blocking Hello!")
          }
        }
      }
    }
  }

  test("fails to restart if the recompilation fails") {
    val changedVersion =
      """object Main { // compile error
     """.stripMargin

    TestUtil.withinWorkspace { workspace =>
      val originalVersion =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Blocking Hello!")
           |    synchronized(wait())
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(originalVersion))

      loadBspState(workspace, List(project), logger) { state =>
        // start debug session and the immediately disconnect from it
        state.withDebugSession(project, mainClassParams("Main")) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- Task {
              val sources = state.toTestState.getProjectFor(project).sources
              val mainFile = sources.head.resolve("main/scala/Main.scala")
              Files.write(mainFile.underlying, changedVersion.getBytes(StandardCharsets.UTF_8))
            }
            _ <- client.restart()
            _ <- client.initialize()
            launched <- client.launch()
            _ <- client.disconnect()
          } yield {
            launched match {
              case Success(_) =>
                fail("Debuggee should not start when a source file is uncompilable")
              case Failure(message) =>
                assertNoDiff(message, "Could not start debuggee")
            }
          }
        }
      }
    }
  }

  test("starts test suites") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-1.0", workspace, logger) { build =>
        val project = build.projectFor("test-project-test")
        val testFilters = List(
          "hello.JUnitTest",
          "hello.ScalaCheckTest",
          "hello.ScalaTestTest",
          "hello.Specs2Test",
          "hello.UTestTest"
        )

        val output = build.state.withDebugSession(project, testSuiteParams(testFilters)) { client =>
          for {
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            _ <- client.disconnect()
            output <- client.allOutput
          } yield output
        }

        testFilters.foreach { testSuite =>
          assert(output.contains(s"All tests in $testSuite passed"))
        }
      }
    }
  }

  def mainClassParams(mainClass: String): bsp.BuildTargetIdentifier => bsp.DebugSessionParams =
    target => {
      val targets = List(target)
      val data = bsp.ScalaMainClass(mainClass, Nil, Nil)
      val json = bsp.ScalaMainClass.encodeScalaMainClass(data)
      val parameters = bsp.LaunchParameters(scalaMainClass, json)
      bsp.DebugSessionParams(targets, parameters)
    }

  def testSuiteParams(filters: List[String]): bsp.BuildTargetIdentifier => bsp.DebugSessionParams =
    target => {
      import io.circe.syntax._
      val targets = List(target)
      val json = filters.asJson
      val parameters = bsp.LaunchParameters(scalaTestSuites, json)
      bsp.DebugSessionParams(targets, parameters)
    }
}
