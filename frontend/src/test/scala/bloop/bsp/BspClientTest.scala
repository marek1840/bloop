package bloop.bsp

import java.io.File
import java.nio.file.Files
import java.util.concurrent.{ConcurrentHashMap, ExecutionException}

import bloop.cli.{Commands, CommonOptions}
import bloop.data.Project
import bloop.engine.State
import bloop.engine.caches.ResultsCache
import bloop.internal.build.BuildInfo
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspClientLogger, DebugFilter, RecordingLogger, Slf4jAdapter}
import bloop.util.TestUtil
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.endpoints
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import monix.{eval => me}
import org.scalasbt.ipcsocket.Win32NamedPipeSocket
import sbt.internal.util.MessageOnlyException

import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc.{BaseProtocolMessage, LanguageClient, LanguageServer, Response, Services}

object BspClientTest {
  private implicit val ctx: DebugFilter = DebugFilter.Bsp
  def cleanUpLastResources(cmd: Commands.ValidatedBsp): Unit = {
    cmd match {
      case cmd: Commands.WindowsLocalBsp => ()
      case cmd: Commands.UnixLocalBsp =>
        // We delete the socket file created by the BSP communication
        if (!Files.exists(cmd.socket.underlying)) ()
        else Files.delete(cmd.socket.underlying)
      case cmd: Commands.TcpBsp => ()
    }
  }

  def setupBspCommand(
      cmd: Commands.ValidatedBsp,
      cwd: AbsolutePath,
      configDir: AbsolutePath
  ): Commands.ValidatedBsp = {
    val common = cmd.cliOptions.common.copy(workingDirectory = cwd.syntax)
    val cliOptions = cmd.cliOptions.copy(configDir = Some(configDir.underlying), common = common)
    cmd match {
      case cmd: Commands.WindowsLocalBsp => cmd.copy(cliOptions = cliOptions)
      case cmd: Commands.UnixLocalBsp => cmd.copy(cliOptions = cliOptions)
      case cmd: Commands.TcpBsp => cmd.copy(cliOptions = cliOptions)
    }
  }

  // We limit the scheduler on purpose so that we don't have any thread leak.
  val scheduler: Scheduler = Scheduler(
    java.util.concurrent.Executors.newFixedThreadPool(4),
    ExecutionModel.AlwaysAsyncExecution
  )

  type TestLogger = Slf4jAdapter[RecordingLogger]
  def runTest[T](
      cmd: Commands.ValidatedBsp,
      configDirectory: AbsolutePath,
      logger0: BspClientLogger[_],
      customServices: Services => Services = identity[Services],
      allowError: Boolean = false,
      reusePreviousState: Boolean = false,
      addDiagnosticsHandler: Boolean = true
  )(runEndpoints: LanguageClient => me.Task[Either[Response.Error, T]]): Unit = {
    val logger = logger0.asVerbose.asInstanceOf[logger0.type]
    // Set an empty results cache and update the state globally
    val state = {
      val id = identity[List[Project]] _
      val state0 = TestUtil.loadTestProject(configDirectory.underlying, id).copy(logger = logger)
      // Return if we plan to reuse it, BSP reloads the state based on the state cache
      if (reusePreviousState) state0
      else {
        val state = state0.copy(results = ResultsCache.emptyForTests)
        State.stateCache.updateBuild(state)
      }
    }

    val configPath = RelativePath("bloop-config")
    val bspServer = BspServer.run(cmd, state, configPath, scheduler).runAsync(scheduler)

    val bspClientExecution = establishClientConnection(cmd).flatMap { socket =>
      val in = socket.getInputStream
      val out = socket.getOutputStream

      implicit val lsClient = new LanguageClient(out, logger)
      val messages = BaseProtocolMessage.fromInputStream(in, logger)
      val services = customServices(TestUtil.createTestServices(addDiagnosticsHandler, logger))
      val lsServer = new LanguageServer(messages, lsClient, services, scheduler, logger)
      val runningClientServer = lsServer.startTask.runAsync(scheduler)

      val cwd = configDirectory.underlying.getParent
      val initializeServer = endpoints.Build.initialize.request(
        bsp.InitializeBuildParams(
          "test-bloop-client",
          "1.0.0",
          BuildInfo.bspVersion,
          rootUri = bsp.Uri(cwd.toAbsolutePath.toUri),
          capabilities = bsp.BuildClientCapabilities(List("scala")),
          None
        )
      )

      for {
        // Delay the task to let the bloop server go live
        initializeResult <- initializeServer.delayExecution(FiniteDuration(1, "s"))
        _ = endpoints.Build.initialized.notify(bsp.InitializedBuildParams())
        otherCalls <- runEndpoints(lsClient)
        _ <- endpoints.Build.shutdown.request(bsp.Shutdown())
        _ = endpoints.Build.exit.notify(bsp.Exit())
      } yield {
        BspServer.closeSocket(cmd, socket)
        otherCalls match {
          case Right(_) => ()
          case Left(error) if allowError => Left(error)
          case Left(error) => throw new MessageOnlyException(s"Received error ${error}!")
        }
      }
    }

    import scala.concurrent.Await
    import scala.concurrent.duration.FiniteDuration
    val bspClient = bspClientExecution.runAsync(scheduler)

    try {
      // The timeout for all our bsp tests, no matter what operation they run, is 60s
      Await.result(bspClient, FiniteDuration(60, "s"))
      Await.result(bspServer, FiniteDuration(60, "s"))
    } catch {
      case e: ExecutionException => throw e.getCause
      case t: Throwable => throw t
    } finally {
      cleanUpLastResources(cmd)
    }
    ()
  }

  private def establishClientConnection(cmd: Commands.ValidatedBsp): me.Task[java.net.Socket] = {
    import org.scalasbt.ipcsocket.UnixDomainSocket
    val connectToServer = me.Task {
      cmd match {
        case cmd: Commands.WindowsLocalBsp => new Win32NamedPipeSocket(cmd.pipeName)
        case cmd: Commands.UnixLocalBsp => new UnixDomainSocket(cmd.socket.syntax)
        case cmd: Commands.TcpBsp => new java.net.Socket(cmd.host, cmd.port)
      }
    }
    retryBackoff(connectToServer, 3, FiniteDuration(1, "s"))
  }

  // Courtesy of @olafurpg
  def retryBackoff[A](
      source: me.Task[A],
      maxRetries: Int,
      firstDelay: FiniteDuration
  ): me.Task[A] = {
    source.onErrorHandleWith {
      case ex: Exception =>
        if (maxRetries > 0)
          // Recursive call, it's OK as Monix is stack-safe
          retryBackoff(source, maxRetries - 1, firstDelay * 2)
            .delayExecution(firstDelay)
        else me.Task.raiseError(ex)
    }
  }

  def testCompile(
      bspCmd: Commands.ValidatedBsp,
      compileTarget: bsp.BuildTargetIdentifier => Boolean
  ): Unit = {
    val compilationResults = new StringBuilder()
    val logger = new BspClientLogger(new RecordingLogger)

    def exhaustiveTestCompile(target: bsp.BuildTarget)(implicit client: LanguageClient) = {
      def compileProject: Task[Either[Response.Error, bsp.CompileResult]] = {
        endpoints.BuildTarget.compile
          .request(bsp.CompileParams(List(target.id), None, None))
          .map {
            case Right(r) => compilationResults.++=(s"${r.statusCode}"); Right(r)
            case Left(e) => Left(e)
          }
      }

      val sourceDir = sourceDirectoryOf(AbsolutePath(ProjectUris.toPath(target.id.uri)))
      Files.createDirectories(sourceDir.underlying)
      val testIncrementalCompilationFile =
        sourceDir.resolve("TestIncrementalCompilation.scala")
      val testIncrementalCompilationFile2 =
        sourceDir.resolve("TestIncrementalCompilation2.scala")
      val testWarningFile = sourceDir.resolve("TestWarning.scala")

      val testSourceDir =
        testSourceDirectoryOf(AbsolutePath(ProjectUris.toPath(target.id.uri)))
      Files.createDirectories(testSourceDir.underlying)
      val junitTestSubclassFile = testSourceDir.resolve("JUnitTestSubclass.scala")

      def deleteJUnitTestSubclassFile(): Boolean =
        Files.deleteIfExists(junitTestSubclassFile.underlying)
      def deleteTestIncrementalCompilationFiles(): Unit = {
        Files.deleteIfExists(testWarningFile.underlying)
        Files.deleteIfExists(testIncrementalCompilationFile.underlying)
        Files.deleteIfExists(testIncrementalCompilationFile2.underlying)
        ()
      }

      val driver = ???
      val deleteAllResources =
        Task { deleteTestIncrementalCompilationFiles(); deleteJUnitTestSubclassFile(); () }
      driver.doOnCancel(deleteAllResources).doOnFinish(_ => deleteAllResources)
    }

    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            workspaceTargets.targets.find(t => compileTarget(t.id)) match {
              case Some(t) => exhaustiveTestCompile(t)
              case None => Task.now(Left(Response.internalError(s"Missing '$MainProject'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed with $error.")))
        }
      }
    }

    val startedTask = scala.collection.mutable.HashSet[bsp.TaskId]()
    val stringifiedDiagnostics = new ConcurrentHashMap[bsp.BuildTargetIdentifier, StringBuilder]()
    val latestTaskIdPerTarget = new ConcurrentHashMap[bsp.BuildTargetIdentifier, String]()

    val addServicesTest = { (s: Services) =>
      val services = s
        .notification(endpoints.Build.taskStart) { taskStart =>
          taskStart.dataKind match {
            case Some(bsp.TaskDataKind.CompileTask) =>
              val json = taskStart.data.get
              bsp.CompileTask.decodeCompileTask(json.hcursor) match {
                case Left(failure) => ???
                case Right(compileTask) =>
                  // Keep the id of the started compilation task up to date to associate diagnostics
                  latestTaskIdPerTarget.compute(
                    compileTask.target,
                    (btid, _) => taskStart.taskId.id
                  )

                  ???
              }
            case _ => ???
          }
        }
        .notification(endpoints.Build.taskFinish) { taskFinish =>
          taskFinish.dataKind match {
            case Some(bsp.TaskDataKind.CompileReport) =>
              val json = taskFinish.data.get
              bsp.CompileReport.decodeCompileReport(json.hcursor) match {
                case Left(failure) => ???
                case Right(report) => ???
              }
            case _ => ???
          }
        }
        .notification(endpoints.Build.publishDiagnostics) {
          case p @ bsp.PublishDiagnosticsParams(tid, btid, _, diagnostics, reset) =>
            stringifiedDiagnostics.compute(
              btid,
              (_, builder0) => {
                val builder = if (builder0 == null) new StringBuilder() else builder0
                val baseDir = {
                  val wp = CommonOptions.default.workingPath
                  if (wp.underlying.endsWith("frontend")) wp
                  else wp.resolve("frontend")
                }

                //if (!tid.uri.value.startsWith("file:///")) ???

                val latestId = Option(latestTaskIdPerTarget.get(btid)).getOrElse("no-id")
                val relativePath = AbsolutePath(tid.uri.toPath).toRelative(baseDir)
                val canonical = relativePath.toString.replace(File.separatorChar, '/')
                val report =
                  diagnostics.map(_.toString.replace("\n", " ").replace(System.lineSeparator, " "))
                builder
                  .++=(s"#$latestId: $canonical\n")
                  .++=(s"  -> $report\n")
                  .++=(s"  -> reset = $reset\n")
              }
            )
        }
    }

    reportIfError(logger) {
      BspClientTest.runTest(
        bspCmd,
        configDir,
        logger,
        addServicesTest,
        addDiagnosticsHandler = false
      )(c => clientWork(c))

      val mainTarget = bsp.BuildTargetIdentifier(mainProject.bspUri)
      val expectedMainDiagnostics =
        s"""#1: src/test/resources/cross-test-build-0.6/test-project/shared/src/main/scala/hello/App.scala
           |  -> List(Diagnostic(Range(Position(5,8),Position(5,8)),Some(Warning),None,None,local val in method main is never used,None))
           |  -> reset = true
           |#2: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation.scala
           |  -> List(Diagnostic(Range(Position(2,29),Position(2,29)),Some(Error),None,None,type mismatch;  found   : String("")  required: Int,None))
           |  -> reset = true
           |#2: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation2.scala
           |  -> List(Diagnostic(Range(Position(2,68),Position(2,68)),Some(Error),None,None,type mismatch;  found   : Int(1)  required: String,None))
           |  -> reset = true
           |#2: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestWarning.scala
           |  -> List(Diagnostic(Range(Position(2,36),Position(2,36)),Some(Warning),None,None,local val in method foo is never used,None))
           |  -> reset = true
           |#3: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation2.scala
           |  -> List(Diagnostic(Range(Position(2,68),Position(2,68)),Some(Error),None,None,type mismatch;  found   : Int(1)  required: String,None))
           |  -> reset = true
           |#3: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation.scala
           |  -> List()
           |  -> reset = true
           |#3: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestWarning.scala
           |  -> List()
           |  -> reset = true
           |#4: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation2.scala
           |  -> List()
           |  -> reset = true
           |#5: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation.scala
           |  -> List(Diagnostic(Range(Position(2,33),Position(2,33)),Some(Error),None,None,']' expected but ')' found.,None))
           |  -> reset = true
           |#6: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation.scala
           |  -> List()
           |  -> reset = true
           |#7: No diagnostics for $mainTarget
           |#8: No diagnostics for $mainTarget
           |#9: No diagnostics for $mainTarget
         """.stripMargin

      // There must only be 5 compile reports with diagnostics in the main project
      val obtainedMainDiagnostics =
        findDiagnosticsReportUntil(mainTarget, stringifiedDiagnostics, totalMainCompilations)
      TestUtil.assertNoDiff(expectedMainDiagnostics, obtainedMainDiagnostics)

      val testTarget = bsp.BuildTargetIdentifier(testProject.bspUri)
      val expectedTestDiagnostics =
        s"""#1: No diagnostics for $testTarget
           |#2: No diagnostics for $testTarget
           |#3: No diagnostics for $testTarget
           |#4: No diagnostics for $testTarget
           |#5: No diagnostics for $testTarget
           |#6: No diagnostics for $testTarget
         """.stripMargin

      // The compilation of the test project sends no diagnostics whatosever
      val obtainedTestDiagnostics =
        findDiagnosticsReportUntil(testTarget, stringifiedDiagnostics, totalTestCompilations)
      TestUtil.assertNoDiff(expectedTestDiagnostics, obtainedTestDiagnostics)
    }
  }
}
