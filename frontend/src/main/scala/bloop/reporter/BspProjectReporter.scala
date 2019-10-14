package bloop.reporter

import java.io.File

import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.logging.{BspServerLogger, CompilationEvent, ObservedLogger}
import bloop.util.AnalysisUtils
import xsbti.Position
import ch.epfl.scala.bsp
import sbt.util.InterfaceUtil
import xsbti.compile.CompileAnalysis

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Try

final class BspProjectReporter(
    val project: Project,
    override val logger: BspServerLogger,
    cwd: AbsolutePath,
    config: ReporterConfig,
    reportAllPreviousProblems: Boolean,
    override val _problems: mutable.Buffer[ProblemPerPhase] = mutable.ArrayBuffer.empty
) extends Reporter(logger, cwd, config, _problems) {
  private lazy val taskId = logger.nextTaskId

  /** A cycle count, initialized to 0 when it's a no-op. */
  private var cycleCount: Int = 0

  /** A thread-safe map with all the files under compilation. */
  private val compilingFiles = mutable.HashMap.empty[File, Boolean]

  /** A thread-safe map with all the files that have been cleared. */
  private val clearedFilesForClient = TrieMap.empty[File, Boolean]

  /** A thread-safe map with all the files that have been cleared. */
  private val startedPhaseInFile = TrieMap.empty[String, Boolean]

  /** Log a problem in a thread-safe manner. */
  override private[reporter] def logFull(problem0: Problem): Unit = {
    val problem = super.liftFatalWarning(problem0)
    sbt.util.InterfaceUtil.toOption(problem.position.sourceFile()) match {
      case Some(file) =>
        // If it's the first diagnostic for this file, set clear to true
        val clear = clearedFilesForClient.putIfAbsent(file, true).isEmpty
        logger.diagnostic(compilationDiagnostic(project, problem, clear))
      case None =>
        logger.diagnostic(compilationDiagnostic(project, problem, false))
    }
  }

  // Report summary manually via `reportEndCompilation` for BSP clients
  override def printSummary(): Unit = ()

  override def reportCompilationProgress(
      progress: Long,
      total: Long
  ): Unit = {
    val percentage = progress * 100 / total
    // We only report percentages every 5% increments
    val shouldReportPercentage = percentage % 5 == 0
    if (shouldReportPercentage) {
      logger.publishCompilationProgress(
        CompilationEvent.ProgressCompilation(
          project.name,
          project.bspUri,
          taskId,
          progress,
          total,
          percentage
        )
      )
    }
  }

  private var recentlyReportProblemsPerFile: Map[File, List[ProblemPerPhase]] = Map.empty

  override def reportStartCompilation(recentProblems: List[ProblemPerPhase]): Unit = {
    recentlyReportProblemsPerFile = Reporter.groupProblemsByFile(recentProblems)
  }

  override def reportNextPhase(phase: String, sourceFile: File): Unit = {
    super.reportNextPhase(phase, sourceFile)
    filesToPhaseStack.getOrElse(sourceFile, Nil) match {
      case Nil => ()
      case x :: Nil => ()
      case x :: finishedPhase :: xs =>
        // Report recent problems for this source file once a phase has finished
        recentlyReportProblemsPerFile.get(sourceFile).foreach { problems =>
          val unreported = clearProblemsAtPhase(sourceFile, finishedPhase, problems)
          recentlyReportProblemsPerFile =
            recentlyReportProblemsPerFile + (sourceFile -> unreported)
        }
    }
  }

  private case class CycleInputs(
      isLastCycle: Boolean,
      previousSuccessfulProblems: Map[File, List[ProblemPerPhase]]
  )

  /** Holds a thunk that reports the end of the previous incremental cycle. It's added by
   * `reportEndIncrementalCycle` and we don't run it eagerly because we need to ensure that
   * all diagnostics (those, for example, coming from previous problemsii and only reported in
   * concrete scenarios) are sent in between task start and task end notifications. This guarantee
   * is violated when we report the end eagerly because:
   *
   *   1. We need to run `reportPreviousProblems` with a value of `reportAllPreviousProblems` at
   *      the very end of compilation (when the last incremental cycle has finished); and
   *   2. There is no way to know if an incremental cycle will be the last one in
   *      `reportEndIncrementalCycle`. We work around this limitation with this approach, so that
   *      when the thunk is run from `reportStartIncrementalCycle` we know a new cycle is coming
   *      and when it's run from `reportEndIncrementalCompilation` we know it's the last cycle.
   */
  private var reportEndPreviousCycleThunk: CycleInputs => Option[bsp.StatusCode] => Unit =
    (_: CycleInputs) => (_: Option[bsp.StatusCode]) => ()

  override def reportStartIncrementalCycle(sources: Seq[File], outputDirs: Seq[File]): Unit = {
    cycleCount += 1
    reportEndPreviousCycleThunk(CycleInputs(false, Map.empty))(None)
    val msg = Reporter.compilationMsgFor(project.name, sources)
    logger.publishCompilationStart(
      CompilationEvent.StartCompilation(project.name, project.bspUri, msg, taskId)
    )
    sources.foreach(sourceFile => compilingFiles.+=(sourceFile -> true))
  }

  private def clearProblemsAtPhase(
      source: File,
      finishedPhase: String,
      problems: List[ProblemPerPhase]
  ): List[ProblemPerPhase] = {
    problems.filterNot {
      case pp @ ProblemPerPhase(problem, phaseOfProblem) =>
        phaseOfProblem match {
          case Some(phase) =>
            if (finishedPhase != phase) false
            else {
              val clear = clearedFilesForClient.putIfAbsent(source, true).isEmpty
              if (clear)
                logger.noDiagnostic(CompilationEvent.NoDiagnostic(project.bspUri, source))
              true // Always mark as processed if the phases coincide
            }
          case None => false
        }
    }
  }

  /**
   * Defines the logic to report remaining problems that were:
   *
   *   1. received from a previous, successful analysis read from disk.
   *   2. received from a previous, successful incremental compiler run.
   *   2. received from a previous compiler run that may or may not have failed.
   *
   * @param reportProblemsForTheFirstTime Whether we should report all problems known for a source
   *                                      file. This is typically true whenever the server starts
   *                                      up and it has not yet compiled a target for a client.
   */
  private def reportRemainingProblems(
      reportProblemsForTheFirstTime: Boolean,
      previousSuccessfulProblemsAtEnd: Map[File, List[ProblemPerPhase]]
  ): Unit = {
    recentlyReportProblemsPerFile.foreach {
      case (sourceFile, problemsPerFile) =>
        if (!sourceFile.exists()) {
          // Clear diagnostics if file doesn't exist anymore
          logger.noDiagnostic(CompilationEvent.NoDiagnostic(project.bspUri, sourceFile))
        } else if (clearedFilesForClient.contains(sourceFile)) {
          // Ignore, if file has been cleared then > 0 diagnostics have been reported
          ()
        } else if (compilingFiles.contains(sourceFile)) {
          // Log no diagnostic if there was a problem in a file that now compiled without problems
          logger.noDiagnostic(CompilationEvent.NoDiagnostic(project.bspUri, sourceFile))
        } else {
          if (reportProblemsForTheFirstTime) {
            // Log all problems received from analysis; this is 1st compilation of this target
            reportAllProblems(sourceFile, problemsPerFile)
          } else {
            previousSuccessfulProblemsAtEnd.get(sourceFile) match {
              case Some(problemsInPreviousAnalysis) =>
                if (problemsInPreviousAnalysis.map(_.problem) == problemsPerFile.map(_.problem)) {
                  // If problems are the same, diagnostics in the editor are up-to-date, do nothing
                  ()
                } else {
                  // Otherwise, log the diagnostics that were known in the previous iteration
                  problemsInPreviousAnalysis.foreach {
                    case ProblemPerPhase(problem, _) =>
                      val clear = clearedFilesForClient.putIfAbsent(sourceFile, true).isEmpty
                      logger.diagnostic(compilationDiagnostic(project, problem, clear))
                  }
                }

              case None =>
                logger.noDiagnostic(CompilationEvent.NoDiagnostic(project.bspUri, sourceFile))
            }
          }
        }
    }
  }

  private def reportAllProblems(sourceFile: File, problems: List[ProblemPerPhase]): Unit = {
    problems.foreach {
      case ProblemPerPhase(problem, _) =>
        val clear = clearedFilesForClient.putIfAbsent(sourceFile, true).isEmpty
        logger.diagnostic(compilationDiagnostic(project, problem, clear))
    }
  }

  override def reportEndIncrementalCycle(durationMs: Long, result: Try[Unit]): Unit = {
    val codeRightAfterCycle = result match {
      case scala.util.Success(()) => bsp.StatusCode.Ok
      case scala.util.Failure(_: xsbti.CompileCancelled) => bsp.StatusCode.Cancelled
      case scala.util.Failure(_) => bsp.StatusCode.Error
    }

    // Add a thunk that we will run whenever we know if this is the last cycle or not
    reportEndPreviousCycleThunk = (inputs: CycleInputs) => {
      (finalCompilationStatusCode: Option[bsp.StatusCode]) => {
        val statusCode = finalCompilationStatusCode.getOrElse(codeRightAfterCycle)
        if (!inputs.isLastCycle) reportRemainingProblems(false, Map.empty)
        else reportRemainingProblems(reportAllPreviousProblems, inputs.previousSuccessfulProblems)
        val liftedProblems = allProblems.toIterator.map(super.liftFatalWarning(_)).toList
        logger.publishCompilationEnd(
          CompilationEvent.EndCompilation(
            project.name,
            project.bspUri,
            taskId,
            liftedProblems,
            statusCode
          )
        )
      }
    }
  }

  override def reportCancelledCompilation(): Unit = ()

  override def reportEndCompilation(
      previousSuccessfulProblems: List[ProblemPerPhase],
      code: bsp.StatusCode
  ): Unit = {
    val problemsInPreviousAnalysisPerFile = Reporter.groupProblemsByFile(previousSuccessfulProblems)

    if (cycleCount == 0) {
      // When no-op, we keep reporting the start and the end of compilation for consistency
      val startMsg = s"Start no-op compilation for ${project.name}"
      logger.publishCompilationStart(
        CompilationEvent.StartCompilation(project.name, project.bspUri, startMsg, taskId)
      )

      recentlyReportProblemsPerFile.foreach {
        case (sourceFile, problemsPerFile) if reportAllPreviousProblems =>
          reportAllProblems(sourceFile, problemsPerFile)
        case (sourceFile, problemsPerFile) =>
          problemsInPreviousAnalysisPerFile.get(sourceFile) match {
            case Some(problemsInPreviousAnalysis) =>
              if (problemsInPreviousAnalysis.map(_.problem) == problemsPerFile.map(_.problem)) {
                // If problems are the same, diagnostics in the editor are up-to-date, do nothing
                ()
              } else {
                // Otherwise, log the diagnostics that were known in the previous successful iteration
                problemsInPreviousAnalysis.foreach {
                  case ProblemPerPhase(problem, _) =>
                    val clear = clearedFilesForClient.putIfAbsent(sourceFile, true).isEmpty
                    logger.diagnostic(compilationDiagnostic(project, problem, clear))
                }
              }

            case None =>
              logger.noDiagnostic(CompilationEvent.NoDiagnostic(project.bspUri, sourceFile))
          }
      }

      val liftedProblems = allProblems.toIterator.map(super.liftFatalWarning(_)).toList
      logger.publishCompilationEnd(
        CompilationEvent.EndCompilation(project.name, project.bspUri, taskId, liftedProblems, code)
      )
    } else {
      // Great, let's report the pending end incremental cycle as the last one
      reportEndPreviousCycleThunk(CycleInputs(true, problemsInPreviousAnalysisPerFile))(Some(code))
    }

    // Clear the state of files with problems at the end of compilation
    clearedFilesForClient.clear()
    compilingFiles.clear()
    super.reportEndCompilation(previousSuccessfulProblems, code)
  }

  private def compilationDiagnostic(
      project: Project,
      problem: xsbti.Problem,
      clear: Boolean
  ): CompilationEvent.Diagnostic = {
    val isOneBased = !project.scalaInstance.exists(_.isDotty)
    CompilationEvent.Diagnostic(project.bspUri, problem, clear, isOneBased)
  }
}
