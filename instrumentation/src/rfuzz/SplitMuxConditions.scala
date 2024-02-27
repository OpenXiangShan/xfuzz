package rfuzz

import firrtl.Mappers._
import firrtl._
import firrtl.ir._
import firrtl.stage.TransformManager.TransformDependency

import scala.collection.mutable

// Removes compound expressions from mux conditions
// Ensures they are a reference
// Borrows from Firrtl's SplitExpressions
class SplitMuxConditions extends Transform with DependencyAPIMigration {
  override def prerequisites: Seq[TransformDependency] = firrtl.stage.Forms.LowForm
  override def optionalPrerequisites = firrtl.stage.Forms.LowFormOptimized
  override def optionalPrerequisiteOf: Seq[TransformDependency] = firrtl.stage.Forms.LowEmitters
  override def invalidates(a: Transform): Boolean = false

  private def isRef(expr: Expression): Boolean = expr match {
    case ref @ (_: WRef | _: WSubField | _: WSubIndex) => true
    case _ => false
  }

  private def onModule(mod: Module): Module = {
    val namespace = Namespace(mod)
    def onStmt(s: Statement): Statement = {
      val stmts = mutable.ArrayBuffer.empty[Statement]
      def onExpr(e: Expression): Expression = e mapExpr onExpr match {
        case Mux(cond, tval, fval, mtpe) if !isRef(cond) =>
          val n = DefNode(Utils.get_info(s), namespace.newTemp, cond)
          stmts.append(n)
          Mux(WRef(n.name, cond.tpe, NodeKind, SourceFlow), tval, fval, mtpe)
        case mux: Mux =>
          mux
        case other => other
      }

      stmts += s.mapExpr(onExpr).mapStmt(onStmt)
      stmts.size match {
        case 1 => stmts.head
        case _ => Block(stmts.toSeq)
      }
    }
    mod.copy(body = onStmt(mod.body))
  }
  def execute(state: CircuitState): CircuitState = {
    state.copy(circuit = state.circuit.copy(modules = state.circuit.modules map {
      case mod: Module => onModule(mod)
      case ext: ExtModule => ext
    }))
  }
}