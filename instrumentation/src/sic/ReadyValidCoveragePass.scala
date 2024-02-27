// Copyright 2021 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package sic

import chiseltest.coverage.CoverageInfo
import firrtl._
import firrtl.annotations._
import firrtl.ir.{Block, DefNode, NoInfo}
import firrtl.options.Dependency
import firrtl.stage.TransformManager.TransformDependency
import firrtl.transforms.DedupModules
import xfuzz.{CoverInfo, CoverPointAnnotation}

import scala.collection.mutable


case class ReadyValidCoverageAnnotation(target: ReferenceTarget, bundle: String)
  extends SingleTargetAnnotation[ReferenceTarget]
    with CoverageInfo {
  override def duplicate(n: ReferenceTarget) = copy(target = n)
}

case object SkipReadyValidCoverageAnnotation extends NoTargetAnnotation

object ReadyValidCoveragePass extends Transform with DependencyAPIMigration {
   val Prefix = "r"

   override def prerequisites: Seq[TransformDependency] = Seq(Dependency[DedupModules])

   // we need to run before types are lowered in order to detect the read/valid bundles
   override def optionalPrerequisiteOf: Seq[TransformDependency] = Seq(Dependency(firrtl.passes.LowerTypes))

   override def invalidates(a: Transform): Boolean = false

   override protected def execute(state: CircuitState): CircuitState = {
     if(state.annotations.contains(SkipReadyValidCoverageAnnotation)) {
       logger.info("[ReadyValidCoverage] skipping due to SkipReadyValidCoverageAnnotation annotation")
       return state
     }
     val newAnnos = mutable.ListBuffer[Annotation]()
     val c = CircuitTarget(state.circuit.main)
     val ignoreMods = Coverage.collectModulesToIgnore(state)
     val circuit = state.circuit.mapModule(onModule(_, c, newAnnos, ignoreMods))
     val annos = newAnnos.toList ++ state.annotations
     CircuitState(circuit, annos)
   }

  private case class ModuleCtx(
    annos:     mutable.ListBuffer[Annotation],
    namespace: Namespace,
    m:         ModuleTarget,
    clk:       ir.Expression)

   private def onModule(m: ir.DefModule, c: CircuitTarget, annos: mutable.ListBuffer[Annotation], ignore: Set[String]): ir.DefModule = m match {
     case mod: ir.Module if !ignore(mod.name) =>
       Builder.findClock(mod, logger) match {
         case Some(clock) =>
           val fires = analyzePorts(mod.ports)
           if(fires.isEmpty) { mod } else {
             val namespace = Namespace(mod)
             namespace.newName(Prefix)
             val ctx = ModuleCtx(annos, namespace, c.module(mod.name), clock)
             val covers = fires.map{ case (parent, fire) => addCover(parent.serialize, fire, ctx) }
             annos ++= covers.map(_._2)
             val newBody = ir.Block(mod.body +: covers.map(_._1))
             mod.copy(body=newBody)
           }
         case None =>
           mod
       }

     case other => other
   }

  private def addCover(bundle: String, fire: ir.Expression, ctx: ModuleCtx): (ir.Statement, ReadyValidCoverageAnnotation) = {
    val node = DefNode(NoInfo, ctx.namespace.newTemp, fire)
    ctx.annos.prepend(CoverPointAnnotation(ComponentName(node.name, ctx.m.toNamed), CoverInfo("ready_valid")))
    val name = ctx.namespace.newName(Prefix)
    val anno = ReadyValidCoverageAnnotation(ctx.m.ref(name), bundle)
    if (Builder.enableVerificationIR) {
      val cover = ir.Verification(ir.Formal.Cover, ir.NoInfo, ctx.clk, fire, Utils.True(), ir.StringLit(""), name)
      (Block(cover, node), anno)
    }
    else {
      (node, anno)
    }
  }

    /** finds any ready/valid bundles that are part of the IO and returns their reference + the fire expression */
   private def analyzePorts(ports: Seq[ir.Port]): Seq[(ir.RefLikeExpression, ir.Expression)] = ports.flatMap { port =>
      analyzePorts(ir.Reference(port))
   }

   private def analyzePorts(parent: ir.RefLikeExpression): Seq[(ir.RefLikeExpression, ir.Expression)] = parent.tpe match {
     case ir.BundleType(fields) =>
       fields.map(_.name).sorted match {
         case Seq("bits", "ready", "valid") =>
           val fire = Utils.and(ir.SubField(parent, "ready", Utils.BoolType), ir.SubField(parent, "valid", Utils.BoolType))
           List((parent, fire))
         case _ =>
           fields.flatMap(f => analyzePorts(ir.SubField(parent, f.name, f.tpe)))
       }
     case ir.VectorType(tpe, _) => List() // ignoring vector types for now
     case _ => List()
   }
 }
