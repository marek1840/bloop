package bloop.bsp

import java.net.{Socket, URI}

import bloop.engine.State
import bloop.config.Config
import bloop.io.AbsolutePath
import bloop.cli.{BspProtocol, ExitStatus}
import bloop.util.{TestProject, TestUtil}
import bloop.logging.RecordingLogger
import bloop.internal.build.BuildInfo

import java.nio.file.Path

import scala.concurrent.duration.FiniteDuration

object TcpBspProtocolSpec extends BspProtocolSpec(BspProtocol.Tcp)
object LocalBspProtocolSpec extends BspProtocolSpec(BspProtocol.Local)

class BspProtocolSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {
  import ch.epfl.scala.bsp

  test("starts a debug session") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-0.6", workspace, logger) { build =>
        val project = build.projectFor("test-project-test")
        val client = build.state.startDebugSession(project, "Foo")

        TestUtil.await(FiniteDuration(1, "s"))(client.initialize())
      }
    }
  }

}
