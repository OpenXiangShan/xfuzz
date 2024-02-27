// Copyright 2021 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package sic.passes


import sic.{AllEmitters, Coverage, Builder}
import firrtl._
import firrtl.annotations._
import firrtl.options.Dependency
import firrtl.transforms._

import scala.collection.mutable

/** adds an assumption to the toplevel module that all resets are active in the first cycle */
object AddResetAssumptionPass extends Transform with DependencyAPIMigration {
  // run on lowered firrtl
  override def prerequisites = Seq(
    Dependency(firrtl.passes.ExpandWhens), Dependency(firrtl.passes.LowerTypes),
    Dependency(firrtl.transforms.RemoveReset),
    // try to work around dead code elimination removing our registers
    Dependency[firrtl.transforms.DeadCodeElimination]
  )
  override def invalidates(a: Transform) = false
  // since we generate PresetRegAnnotations, we need to run after preset propagation
  override def optionalPrerequisites = Seq(Dependency[PropagatePresetAnnotations]) ++ Coverage.AllPasses
  // we want to run before the actual Verilog is emitted
  override def optionalPrerequisiteOf = AllEmitters()

  override def execute(state: CircuitState): CircuitState = {
    val annos = mutable.ListBuffer[Annotation]()
    val c = CircuitTarget(state.circuit.main)
    val modules = state.circuit.modules.map {
      case mod: ir.Module => onModule(c, mod, annos)
      case other => other
    }
    val circuit = state.circuit.copy(modules = modules)
    state.copy(circuit = circuit, annotations = annos.toList ++: state.annotations)
  }

  private def onModule(c: CircuitTarget, m: ir.Module, annos: mutable.ListBuffer[Annotation]): ir.Module = {
    val clock = Builder.findClock(m ,logger)
    if(clock.isEmpty) return m

    // create a register to know when we are in the "init" cycle
    val namespace = Namespace(m)
    val reg = ir.DefRegister(ir.NoInfo, namespace.newName("isInitCycle"),
      Utils.BoolType, clock.get, reset = Utils.False(), init = Utils.True())
    val regRef = ir.Reference(reg)
    val next = ir.Connect(ir.NoInfo, regRef, Utils.False())
    val notInit = ir.DefNode(ir.NoInfo, namespace.newName("enCover"), Utils.not(regRef))
    annos.append(PresetRegAnnotation(c.module(m.name).ref(reg.name)))

    val isMain = c.circuit == m.name
    val resetAssumptions = if(isMain) {
      val resets = Builder.findResets(m)
      // assume that isInitCycle => reset
      resets.map { r =>
        ir.Verification(ir.Formal.Assume, ir.NoInfo, clock.get, r, regRef, ir.StringLit(""), namespace.newName(r.serialize + "_active"))
      }
    } else { List() }

    // make sure the we do not cover anything in the first cycle
    val guardedCovers = onStmt(m.body, ir.Reference(notInit))
    val body = ir.Block(Seq(reg, next, notInit) ++ resetAssumptions ++ Seq(guardedCovers))

    m.copy(body = body)
  }

  private def onStmt(s: ir.Statement, en: ir.Expression): ir.Statement = s match {
    case v: ir.Verification if v.op == ir.Formal.Cover => v.withEn(Utils.and(en, v.en))
    case other => other.mapStmt(onStmt(_, en))
  }
}
