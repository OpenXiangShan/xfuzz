// Copyright 2021 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package sic

import sic.passes.{AddResetAssumptionPass, ChangeMainPass, MakeMainAnnotation, RandomStateInit}
import firrtl.annotations.{CircuitTarget, ModuleTarget}
import firrtl.options._
import firrtl.stage.RunFirrtlTransformAnnotation
import rfuzz.DoNotProfileModule

final class CoverageShellOptions extends RegisteredLibrary {
  override def name = "Coverage"

  override def options = Seq(
    new ShellOption[Unit](
      longOption = "line-coverage",
      toAnnotationSeq = _ => LineCoverage.annotations ++ Common,
      helpText = "enable line coverage instrumentation"
    ),
    new ShellOption[Unit](
      longOption = "fsm-coverage",
      toAnnotationSeq = _ => FsmCoverage.annotations ++ Common,
      helpText = "enable finite state machine coverage instrumentation"
    ),
    new ShellOption[Unit](
      longOption = "toggle-coverage",
      toAnnotationSeq = _ => ToggleCoverage.all ++ Common,
      helpText = "enable toggle coverage instrumentation for all signals"
    ),
    new ShellOption[Unit](
      longOption = "toggle-coverage-ports",
      toAnnotationSeq = _ => ToggleCoverage.ports ++ Common,
      helpText = "enable toggle coverage instrumentation for all I/O ports"
    ),
    new ShellOption[Unit](
      longOption = "toggle-coverage-registers",
      toAnnotationSeq = _ => ToggleCoverage.registers ++ Common,
      helpText = "enable toggle coverage instrumentation for all registers"
    ),
    new ShellOption[Unit](
      longOption = "toggle-coverage-memories",
      toAnnotationSeq = _ => ToggleCoverage.memories ++ Common,
      helpText = "enable toggle coverage instrumentation for all memory ports"
    ),
    new ShellOption[Unit](
      longOption = "toggle-coverage-wires",
      toAnnotationSeq = _ => ToggleCoverage.wires ++ Common,
      helpText = "enable toggle coverage instrumentation for all wires"
    ),
    new ShellOption[Unit](
      longOption = "ready-valid-coverage",
      toAnnotationSeq = _ => ReadyValidCoverage.annotations ++ Common,
      helpText = "enable coverage of read valid interfaces on I/O ports"
    ),
    new ShellOption[String](
      longOption = "do-not-cover",
      toAnnotationSeq = a => Seq(DoNotProfileModule(parseModuleTarget(a).toNamed)),
      helpText = "select module which should not be instrumented with coverage",
      helpValueName = Some("<circuit:module>")
    ),
    new ShellOption[Unit](
      longOption = "emit-cover-info",
      toAnnotationSeq = _ => Seq(RunFirrtlTransformAnnotation(Dependency(CoverageInfoEmitter))),
      helpText = "write coverage information to a .cover.json file"
    ),
    new ShellOption[Unit](
      longOption = "random-state-init",
      toAnnotationSeq = _ => Seq(RunFirrtlTransformAnnotation(Dependency(RandomStateInit))),
      helpText = "initializes registers and memories with random (at compile time) values"
    ),
    new ShellOption[Unit](
      longOption = "formal-cover",
      toAnnotationSeq = _ => Seq(RunFirrtlTransformAnnotation(Dependency(RandomStateInit)),
        RunFirrtlTransformAnnotation(Dependency(AddResetAssumptionPass))),
      helpText = "prepares the circuit for formal cover trace generation"
    ),
    new ShellOption[String](
      longOption = "make-main",
      toAnnotationSeq = a => Seq(MakeMainAnnotation(parseModuleTarget(a)), RunFirrtlTransformAnnotation(Dependency(ChangeMainPass))),
      helpText = "selects a module to be the main module of the circuit",
      helpValueName = Some("<circuit:module>")
    ),
    new ShellOption[Unit](
      longOption = "add-reset-assumption",
      toAnnotationSeq = _ => Seq(RunFirrtlTransformAnnotation(Dependency(AddResetAssumptionPass))),
      helpText = "adds an assumption to the toplevel module that all resets are active in the first cycle"
    ),
    new ShellOption[String](
      longOption = "remove-covered",
      toAnnotationSeq = a => Seq(LoadCoverageAnnotation(a),
        RunFirrtlTransformAnnotation(Dependency(RemoveCoverPointsPass)),
        RunFirrtlTransformAnnotation(Dependency(FindCoversToRemovePass))),
      helpText = "removes all points that have been covered at least N times",
      helpValueName = Some("<...cove.json>")
    ),
  )

  private def parseModuleTarget(a: String): ModuleTarget = {
    val parts = a.trim.split(':').toSeq
    parts match {
      case Seq(circuit, module) => CircuitTarget(circuit.trim).module(module.trim)
      case _ => throw new RuntimeException(s"Expected format: <circuit:module>, not: $a")
    }
  }

  private val Common = Seq(RunFirrtlTransformAnnotation(Dependency(CoverageStatisticsPass)))
}
