// Copyright 2021 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package sic.passes


import firrtl._
import firrtl.analyses.InstanceKeyGraph
import firrtl.analyses.InstanceKeyGraph.InstanceKey
import firrtl.annotations._
import firrtl.options.Dependency
import firrtl.renamemap.MutableRenameMap
import firrtl.stage.Forms


case class MakeMainAnnotation(target: ModuleTarget) extends SingleTargetAnnotation[ModuleTarget] {
  override def duplicate(n: ModuleTarget) = copy(target=n)
}

object ChangeMainPass extends Transform with DependencyAPIMigration {
  // try to run early
  override def prerequisites = Forms.Checks
  override def invalidates(a: Transform) = false
  // run early
  override def optionalPrerequisiteOf = Seq(
    Dependency[firrtl.transforms.DedupModules],
    Dependency(passes.PullMuxes)
  )

  override def execute(state: CircuitState): CircuitState = {
    state.annotations.collect{ case MakeMainAnnotation(m) => m } match {
      case Seq() =>
        logger.info(s"[ChangeMainPass] no MakeMainAnnotation found, ${state.circuit.main} remains main.")
        state
      case Seq(main) =>
        assert(main.circuit == state.circuit.main)
        if(main.module == state.circuit.main) {
          logger.info(s"[ChangeMainPass] nothing to do, ${state.circuit.main} remains main.")
          state
        } else {
          // change main of circuit
          val circuit = state.circuit.copy(main = main.module)

          // remove all modules that are now unreachable
          val iGraph = InstanceKeyGraph(circuit)
          val reachable = findReachable(circuit.main, iGraph.getChildInstances.toMap)
          val isUnreachable = iGraph.moduleMap.keySet -- reachable
          if(isUnreachable.nonEmpty) {
            logger.info(s"[ChangeMainPass] removing unreachable modules:\n${isUnreachable}")
          }
          val reducedCircuit = circuit.copy(modules = circuit.modules.filterNot(m => isUnreachable(m.name)))

          // rename all annotations
          val rename = MutableRenameMap()
          rename.rename(CircuitTarget(state.circuit.main), CircuitTarget(main.module))
          state.copy(circuit = reducedCircuit, renames = Some(rename))
        }
      case other =>
        throw new RuntimeException(s"[ChangeMainPass] only a single MakeMainAnnotation may be supplied: $other")
    }
  }

  private def findReachable(module: String, childInstances: Map[String, Seq[InstanceKey]]): Set[String] = {
    childInstances(module).flatMap(k => findReachable(k.module, childInstances)).toSet | Set(module)
  }

}
