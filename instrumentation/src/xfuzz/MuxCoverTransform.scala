// This file is mostly adapted from rfuzz.ProfilingTransform.scala.

package xfuzz

import firrtl.Utils.get_info
import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.options.Dependency
import firrtl.stage.TransformManager.TransformDependency
import rfuzz.DoNotProfileModule

import scala.collection.mutable.{ArrayBuffer, ListBuffer}

case class ProfileConfig(topPortName: String)(
  // Returns the optionally mutated input statement and any expressions
  // that should be extracted to the top for profiling
  val processStmt: PartialFunction[(Statement, ListBuffer[String]), (Statement, Seq[Expression])]
)

/**
 * Insert CoverPointAnnotation into the MUX cover points.
 * Let CoverPointTransform to do the wiring or connection to blackboxes.
 */
class MuxCoverTransform extends Transform with DependencyAPIMigration {
  override def prerequisites: Seq[TransformDependency] = Seq(Dependency[rfuzz.SplitMuxConditions])
  override def optionalPrerequisiteOf: Seq[TransformDependency] = Seq(Dependency[CoverPointTransform])
  override def invalidates(a: Transform): Boolean = false

  def containsMux(c: ListBuffer[String])(stmt: Statement): Boolean = {
    var muxFound = false
    def onExpr(expr: Expression): Expression = expr.mapExpr(onExpr) match {
      case m: Mux =>
        muxFound = m.cond.serialize != "reset" && !c.contains(m.cond.serialize)
        m
      case other => other
    }
    stmt.mapExpr(onExpr)
    muxFound
  }
  def extractMuxConditions(c: ListBuffer[String])(stmt: Statement): Seq[Expression] = {
    val conds = ArrayBuffer.empty[Expression]
    def onExpr(expr: Expression): Expression = expr.mapExpr(onExpr) match {
      case mux @ Mux(cond, _,_,_) =>
        conds += cond
        c += cond.serialize
        mux
      case other => other
    }
    stmt.mapExpr(onExpr)
    conds.toSeq
  }

  val autoCoverageConfig = ProfileConfig("auto_cover_out") {
    case (stmt, c) if containsMux(c)(stmt) => (stmt, extractMuxConditions(c)(stmt))
  }
  val configs = Seq(
    autoCoverageConfig
  )

  // Hardwired constants
  val profilePinPrefix = "profilePin"

  private def CatTypes(tpe1: Type, tpe2: Type): Type = (tpe1, tpe2) match {
    case (UIntType(w1), UIntType(w2)) => UIntType(w1 + w2)
    case (SIntType(w1), SIntType(w2)) => UIntType(w1 + w2)
    case other => throw new Exception(s"Unexpected Types $other!")
  }

  // This namespace is based on the ports that exist in the top
  private def onModule(
                        mod: Module,
                        top: String,
                        namespace: Namespace
                      ): (Module, Map[ProfileConfig, Seq[CoverPointAnnotation]]) = {
    // We record Annotations for each profiled signal for each profiling configuration
    val profiledSignals = Map(configs.map(c => c -> ArrayBuffer.empty[CoverPointAnnotation]): _*)
    val localNS = Namespace(mod)

    def onStmt(refNames: ListBuffer[String])(stmt: Statement): Statement = {
      val stmtx = stmt.mapStmt(onStmt(refNames))
      configs.flatMap(c => c.processStmt.lift((stmtx, refNames)).map(c -> _)) match {
        // No profiling on this Statement, just return it
        case Seq() => stmtx
        case Seq((config, (retStmt, signals))) =>
          val (nodes, annos) = signals.map { expr =>
            val node = DefNode(get_info(stmtx), localNS.newTemp, expr)
            val named = ComponentName(node.name, ModuleName(mod.name, CircuitName(top)))
            val pinName = namespace.newName(profilePinPrefix)
            assert(localNS.tryName(pinName), s"Name collision with $pinName in ${mod.name}!")
            val anno = Seq(CoverPointAnnotation(named, CoverInfo("mux")))
            (node, anno)
          }.unzip
          profiledSignals(config) ++= annos.flatten
          Block(retStmt +: nodes)
        case _ =>
          // We don't let multiple configurations match on a statement because they could have
          // different behavior for what should happen to that Statement (eg. removed vs. not
          // removed)
          throw new Exception("Error! Multiple profiling configurations trying to " +
            "profile the same Statement!")
      }
    }

    val bodyx = onStmt(ListBuffer.empty[String])(mod.body)
    (mod.copy(body = bodyx), profiledSignals.view.mapValues(_.toSeq).toMap)
  }

  def execute(state: CircuitState): CircuitState = {

    val dontProfile = state.annotations
      .collect { case DoNotProfileModule(ModuleName(m, _)) => m }
      .toSet
    val top = state.circuit.modules.find(_.name == state.circuit.main).get
    val topNameS = Namespace(top) // used for pins naming

    val (modsx, profiledSignalMaps) = state.circuit.modules.map {
      case mod: Module if !dontProfile(mod.name) => onModule(mod, top.name, topNameS)
      case other => (other, Map.empty[ProfileConfig, Seq[CoverPointAnnotation]])
    }.unzip
    val profiledSignals =
      configs.map(
        c => c -> profiledSignalMaps
          .filterNot(_.isEmpty)
          .map(_.apply(c))
          .reduce(_ ++ _))
        .toMap

    val circuitx = state.circuit.copy(modules = modsx)
    val annosx = state.annotations ++ profiledSignals.flatMap(_._2)

    val result = state.copy(circuit = circuitx, annotations = annosx)
    result
  }
}
