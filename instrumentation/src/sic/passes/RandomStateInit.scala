// Copyright 2021 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package sic.passes


import sic.AllEmitters
import firrtl._
import firrtl.annotations._
import firrtl.options.Dependency
import firrtl.transforms._

import scala.collection.mutable
import scala.util.Random

/** Initializes every register with a (random at compile time) value.
 *  This can be useful when doing formal cover trace generation because
 *  it prevents the solver from choosing specific register initialization
 *  value to achieve coverage instead of choosing proper inputs.
 *
 * */
object RandomStateInit extends Transform with DependencyAPIMigration {
  // run on lowered firrtl
  override def prerequisites = Seq(
    Dependency(firrtl.passes.ExpandWhens), Dependency(firrtl.passes.LowerTypes),
    Dependency(firrtl.transforms.RemoveReset))
  override def invalidates(a: Transform) = false
  // since we generate PresetRegAnnotations, we need to run after preset propagation
  override def optionalPrerequisites = Seq(Dependency[PropagatePresetAnnotations])
  // we want to run before the actual Verilog is emitted
  override def optionalPrerequisiteOf = AllEmitters()

  override def execute(state: CircuitState): CircuitState = {
    val c = CircuitTarget(state.circuit.main)
    val initd = findInitializedMems(state.annotations)
    val modsAndAnnos = state.circuit.modules.map(onModule(_, c, initd))
    // TODO MemorySynthInit annotation does not work with MemoryScalarInitAnnotation :(
    val annos = modsAndAnnos.flatMap(_._2) ++: state.annotations
    val circuit = state.circuit.copy(modules = modsAndAnnos.map(_._1))
    state.copy(circuit = circuit, annotations = annos)
  }

  private def findInitializedMems(annos: AnnotationSeq): Seq[ReferenceTarget] = {
    annos.collect {
      case MemoryScalarInitAnnotation(target, _) => target
      case MemoryArrayInitAnnotation(target, _) => target
      case MemoryFileInlineAnnotation(target, _, _) => target
      case LoadMemoryAnnotation(target, _, _, _) => target.toTarget
    }
  }

  private def onModule(m: ir.DefModule, c: CircuitTarget, initialized: Seq[ReferenceTarget]): (ir.DefModule, Seq[Annotation]) = m match {
    case mod: ir.Module =>
      val isInitd = initialized.filter(_.module == mod.name).map(_.ref).toSet
      val rand = new Random(mod.name.hashCode)
      val annos = mutable.ListBuffer[Annotation]()
      val m = c.module(mod.name)
      val newMod = mod.mapStmt(onStmt(_, annos, m, rand, isInitd))
      (newMod, annos.toList)
    case other => (other, List())
  }

  private def bitsToSigned(unsigned: BigInt, width: Int): BigInt = {
    val isNeg = ((unsigned >> (width - 1)) & 1) == 1
    if(isNeg) {
      val mask = (BigInt(1) << width) - 1
      -1 * (((~unsigned) & mask) + 1)
    } else { unsigned }
  }

  private def onStmt(s: ir.Statement, annos: mutable.ListBuffer[Annotation], m: ModuleTarget, rand: Random, isInitd: String => Boolean): ir.Statement = s match {
    case r: ir.DefRegister =>
      if(r.reset == Utils.False()) {
        val bits = firrtl.bitWidth(r.tpe).toInt

        val init = r.tpe match {
          case _ : ir.SIntType =>
            val initValue = bitsToSigned(BigInt(bits, rand), bits)
            ir.SIntLiteral(initValue, ir.IntWidth(bits))
          case _ => ir.UIntLiteral(BigInt(bits, rand), ir.IntWidth(bits))
        }
        annos.append(PresetRegAnnotation(m.ref(r.name)))
        r.copy(init = init)
      } else {
        logger.warn(s"[${m.module}] Cannot initialize register ${r.name} with reset: ${r.reset.serialize}")
        r
      }
    case mem: ir.DefMemory if !isInitd(mem.name) =>
      val value = BigInt(firrtl.bitWidth(mem.dataType).toInt, rand)
      annos.append(MemoryScalarInitAnnotation(m.ref(mem.name), value))
      mem
    case other => other.mapStmt(onStmt(_, annos, m, rand, isInitd))
  }
}
