// Copyright 2021 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package sic

import chiseltest.coverage.{CoverageInfo, ModuleInstancesAnnotation, TestCoverage}
import firrtl._
import firrtl.annotations.{JsonProtocol, NoTargetAnnotation}
import firrtl.options.{CustomFileEmission, Dependency}
import firrtl.options.Viewer.view
import firrtl.stage.FirrtlOptions


/** Serializes all relevant coverage annotations. */
object CoverageInfoEmitter extends Transform with DependencyAPIMigration {
  override def prerequisites = Seq()
  override def optionalPrerequisites = Coverage.AllPasses ++ Seq(
    Dependency(CoverageStatisticsPass), Dependency(ModuleInstancesPass)
  )
  override def invalidates(a: Transform) = false

  override def execute(state: CircuitState): CircuitState = {
    val (covAnnos, otherAnnos) = state.annotations.partition {
      case _: CoverageInfo => true
      case _: TestCoverage => true
      case _: ModuleInstancesAnnotation => true
      case SkipLineCoverageAnnotation => true
      case SkipToggleCoverageAnnotation => true
      case SkipFsmCoverageAnnotation => true
      case _: FsmInfoAnnotation => true
      // case _: DoNotCoverAnnotation => true
      case WireToggleCoverage => true
      case MemoryToggleCoverage => true
      case RegisterToggleCoverage => true
      case PortToggleCoverage => true
      case _: RemoveCoverAnnotation => true
      case _: LoadCoverageAnnotation => true
      case _ => false
    }

    if(covAnnos.nonEmpty) {
      val str = JsonProtocol.serialize(covAnnos)
      val out = EmittedCoverageInfo(str, state.circuit.main)
      state.copy(annotations =  out +: otherAnnos)
    } else { state }
  }
}

case class EmittedCoverageInfo(str: String, main: String) extends NoTargetAnnotation with CustomFileEmission {
  override protected def baseFileName(annotations: AnnotationSeq): String = {
    view[FirrtlOptions](annotations).outputFileName.getOrElse(main)
  }
  override protected def suffix = Some(".cover.json")
  override def getBytes = str.getBytes
}
