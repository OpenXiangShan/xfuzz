// Copyright 2021 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package sic

import firrtl._
import firrtl.options.Dependency
import firrtl.stage.RunFirrtlTransformAnnotation

object ReadyValidCoverage {
  def annotations: AnnotationSeq = Seq(
    RunFirrtlTransformAnnotation(Dependency(ReadyValidCoveragePass)),
    RunFirrtlTransformAnnotation(Dependency(ModuleInstancesPass))
  )

  def processCoverage(annos: AnnotationSeq): Seq[ReadyValidCoverageData] = {
    val fires = annos.collect{ case a : ReadyValidCoverageAnnotation => a }
    val cov = Coverage.collectTestCoverage(annos).toMap
    val moduleToInst = Coverage.moduleToInstances(annos)

    fires.flatMap { f =>
      val top = f.target.circuit + "."
      moduleToInst(f.target.module).map { inst =>
        val count = cov(Coverage.path(inst, f.target.ref))
        ReadyValidCoverageData(top + Coverage.path(inst, f.bundle), count)
      }
    }
  }

  def textReport(data: Seq[ReadyValidCoverageData]): Iterable[String] = {
    data.map(d => s"${d.name}: ${d.count}")
  }
}

case class ReadyValidCoverageData(name: String, count: Long)