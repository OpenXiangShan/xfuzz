/***************************************************************************************
* Copyright (c) 2020-2024 Institute of Computing Technology, Chinese Academy of Sciences
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xfuzz

import chisel3.experimental.{ChiselAnnotation, RunFirrtlTransform, annotate}
import chisel3.internal.InstanceId
import firrtl.PrimOps.Xor
import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.passes.wiring.{SinkAnnotation, SourceAnnotation, WiringTransform}
import firrtl.stage.RunFirrtlTransformAnnotation
import firrtl.stage.TransformManager.TransformDependency
import firrtl.transforms.{BlackBoxInlineAnno, BlackBoxSourceHelper, DontTouchAllTargets}
import rfuzz._
import sic._

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable

object CoverType extends Enumeration {
  type CoverType = Value
  val Normal, Raw, Multibit = Value
}

case class CoverInfo(name: String, tpe: CoverType.Value = CoverType.Normal) {
  def isRaw: Boolean = tpe == CoverType.Raw
  def isMultibit: Boolean = tpe == CoverType.Multibit
  override def toString: String = name
}

case class CoverPointAnnotation(target: Target, info: CoverInfo) extends SingleTargetAnnotation[Target]
    with DontTouchAllTargets {
  override def duplicate(newTarget: Target): CoverPointAnnotation = this.copy(target = newTarget)
}

class CoverPointMaker(coverPoints: Seq[CoverInfo]) {
  private val coverTypes: Map[CoverInfo, Int] = coverPoints.distinct.map(p => (p, coverPoints.count(_ == p))).toMap
  private val nCoverExpr = coverTypes.values.sum
  // (mod: ExtModule, w: Int)
  private val coverModules = mutable.ListBuffer.empty[(ExtModule, CoverInfo, Int)]
  private val coverTotal = mutable.Map[CoverInfo, Int]().withDefaultValue(0)
  private def covers: Map[CoverInfo, Int] = coverTypes.map{ case (n, _) => (n, coverTotal(n))}

  private def extModuleName(info: CoverInfo, w: Int): String = s"GEN_w${w}_$info"
  private def extModuleIO(w: Int): Seq[(String, Int)] = Seq(("clock", 1), ("reset", 1), ("valid", w))
  private def dpicFuncName(info: CoverInfo): String = s"v_cover_${info.name.toLowerCase}"

  private val extModulePortsCache = mutable.Map.empty[(CoverInfo, Int), Seq[Port]]
  private def getExtModule(info: CoverInfo, w: Int): (ExtModule, Int) = {
    val hashKey = (info, w)
    val ports = extModulePortsCache.getOrElse(hashKey, {
      val bundle: BundleType = BundleType(extModuleIO(w).map { case (n, w) =>
        Field(n, Default, UIntType(IntWidth(w)))
      })
      val ps = bundle.fields.map(f => Port(NoInfo, f.name, Input, f.tpe))
      extModulePortsCache(hashKey) = ps
      ps
    })
    val moduleName = extModuleName(info, w)
    val coverIndex = coverTotal(info)
    val params: Seq[Param] = Seq(IntParam("COVER_INDEX", coverIndex))
    val module = ExtModule(NoInfo, s"${moduleName}_$coverIndex", ports, moduleName, params)
    val coverModule = (module, info, w)
    coverModules += coverModule
    (module, coverIndex)
  }

  // globalInputs: ListBuffer[modName: String, portName: String, width, Width]
  private val globalInputs = mutable.ListBuffer.empty[(String, String, Width)]
  private val modPortCache = mutable.HashMap.empty[(String, String), Expression]
  private def createPort(p: String, tpe: GroundType, m: DefModule): (Expression, Option[DefWire]) = {
    val port = DefWire(NoInfo, p, UIntType(tpe.width))
    globalInputs.append((m.name, port.name, tpe.width))
    (WRef(port), Some(port))
  }
  private def getPort(p: String, tpe: GroundType, m: DefModule): (Expression, Option[DefWire]) = {
    val hashKey = (p, m.name)
    if (modPortCache.contains(hashKey)) {
      (modPortCache(hashKey), None)
    }
    else {
      val portRef = m.ports.collectFirst { case Port(_, `p`, _, _) => WRef(p, tpe) }
      val e = if (portRef.isEmpty) {
        val portRef = m.ports.collectFirst { case Port(_, n, _, _) if n.endsWith(p) => WRef(n, tpe) }
        if (portRef.isDefined) {
          println(s"WARN: using ${portRef.get.name} for $p in module ${m.name}")
          (portRef.get, None)
        }
        else {
          println(s"WARN: Does not find the $p PIN in module ${m.name}. Use global inputs instead.")
          createPort(p, tpe, m)
        }
      } else (portRef.get, None)
      modPortCache(hashKey) = e._1
      e
    }
  }
  private def getPort(tpe: GroundType, dir: Direction, m: DefModule): (Expression, Option[DefWire]) = {
    val portRef = m.ports.collectFirst { case Port(_, p, `dir`, `tpe`) => WRef(p, tpe) }
    if (portRef.isDefined) {
      (portRef.get, None)
    }
    else {
      val portName = tpe.serialize.toLowerCase
      val hashKey = (portName, m.name)
      if (modPortCache.contains(hashKey)) {
        (modPortCache(hashKey), None)
      }
      else {
        println(s"WARN: Does not find the $dir:$tpe PIN in module ${m.name}. Use global inputs instead.")
        val res = createPort(portName, tpe, m)
        modPortCache(hashKey) = res._1
        res
      }
    }
  }

  private def getExprWidth(tpe: Type): Int = {
    val w = tpe match {
      case UIntType(w) => w
      case SIntType(w) => w
      case _ => throw new Exception(s"Cannot mark type $tpe as a cover point.")
    }
    w match {
      case IntWidth(x) => x.toInt
      case _ => throw new Exception(s"Cannot infer width: $w.")
    }
  }

  // collectedPoints: Map[covName: String, ListBuffer[index: Int, count: Int, modName: String, varName: String]]
  private val collectedPoints = mutable.HashMap.empty[CoverInfo, mutable.ListBuffer[(Int, Int, String, String)]]
  private var numSkippedPoints = 0
  private def collected: Int = collectedPoints.map(_._2.size).sum + numSkippedPoints
  private def coverOnStmt(stmt: DefNode, info: CoverInfo, m: DefModule): Statement = {
    // we will skip the obviously constant values (literals)
    if (stmt.value.isInstanceOf[Literal]) {
      numSkippedPoints += 1
      return stmt
    }
    val w = getExprWidth(stmt.value.tpe)
    val (extModule, coverIndex) = getExtModule(info, w)
    val singleBit = w == 1 && !info.isRaw
    val count = if (singleBit || info.isMultibit) w else 1 << w
    coverTotal(info) = coverTotal(info) + count
    if (!collectedPoints.contains(info)) {
      collectedPoints(info) = mutable.ListBuffer.empty[(Int, Int, String, String)]
    }
    collectedPoints(info).append((coverIndex, count, m.name, stmt.value.serialize))
    val modInsName = Namespace(m).newName(info.name) + s"_$coverIndex"
    val extMod = WDefInstance(modInsName, extModule.name)
    val modRef = WRef(extMod)
    // clock and reset: from the parent module
    val (parentClock, clockWire) = getPort(ClockType, Input, m)
    val (parentReset, resetWire) = getPort("reset", ResetType, m)
    val parentConn = clockWire.toSeq ++ resetWire.toSeq ++ Seq(
      Connect(NoInfo, WSubField(modRef, "clock", ClockType), parentClock),
      Connect(NoInfo, WSubField(modRef, "reset", ResetType), parentReset)
    )
    if (singleBit || info.isMultibit) {
      // valid: stmt ^ RegNext(stmt)
      val stmtReg = DefRegister(NoInfo, s"${modInsName}_valid_reg", UIntType(IntWidth(w)),
        parentClock, UIntLiteral(0), UIntLiteral(0))
      val stmtRegConn = Connect(NoInfo, WRef(stmtReg), stmt.value)
      val xor = DoPrim(Xor, Seq(stmt.value, WRef(stmtReg)), Seq(), UIntType(IntWidth(w)))
      val xorDef = DefNode(NoInfo, s"${modInsName}_valid_xor", xor)
      val validConn = Connect(NoInfo, WSubField(modRef, "valid", UIntType(IntWidth(w))), xor)
      Block(Seq(extMod) ++ parentConn ++ Seq(stmtReg, stmtRegConn, xorDef, validConn))
    }
    else {
      // FSM: stmt as the offset
      val validConn = Connect(NoInfo, WSubField(modRef, "valid", UIntType(IntWidth(w))), stmt.value)
      Block(Seq(extMod) ++ parentConn :+ validConn)
    }
  }
  def onStmt(stmt: DefNode, coverNames: Seq[CoverInfo], m: DefModule): Statement = {
    val stmts = coverNames.map(c => coverOnStmt(stmt, c, m))
    Block(stmt +: stmts)
  }

  def writeExtraFiles(): Unit = {
    if (nCoverExpr != collected) {
      val l = nCoverExpr - collected
      throw new Exception(s"Some ($l out of $nCoverExpr) cover points are not collected. Did you miss any expr/anno?")
    }

    val coverCpp = mutable.ListBuffer.empty[String]
    coverCpp += "#ifndef __FIRRTL_COVER_H__"
    coverCpp += "#define __FIRRTL_COVER_H__"
    coverCpp += ""
    coverCpp += "#include <cstdint>"
    coverCpp += ""
    coverCpp += "typedef struct {"
    coverCpp += "        uint8_t* points;"
    coverCpp += "  const uint64_t total;"
    coverCpp += "  const char*    name;"
    coverCpp += "  const char**   point_names;"
    coverCpp += "} FIRRTLCoverPoint;"
    coverCpp += ""
    coverCpp += "typedef struct {"
    coverCpp += "  const FIRRTLCoverPoint cover;"
    coverCpp += "  bool is_feedback;"
    coverCpp += "} FIRRTLCoverPointParam;"
    coverCpp += ""
    coverCpp += s"extern FIRRTLCoverPointParam firrtl_cover[${covers.size}];"
    coverCpp += ""
    if (globalInputs.nonEmpty) {
      globalInputs.map(_._2).distinct.foreach(portName => {
        coverCpp += s"#define COVERAGE_PORT_${portName.toUpperCase}"
      })
      coverCpp += ""
    }
    coverCpp += "#endif // __FIRRTL_COVER_H__"
    coverCpp += ""
    val outputDir = sys.env("NOOP_HOME") + "/build/generated-src"
    Files.createDirectories(Paths.get(outputDir))
    val outputHeaderFile = outputDir + "/firrtl-cover.h"
    Files.write(Paths.get(outputHeaderFile), coverCpp.mkString("\n").getBytes(StandardCharsets.UTF_8))

    coverCpp.clear()
    coverCpp += "#include \"firrtl-cover.h\""
    coverCpp += ""
    coverCpp += "typedef struct {"
    for ((info, coverTotal) <- covers) {
      coverCpp += s"  uint8_t $info[$coverTotal];"
    }
    coverCpp += "} CoverPoints;"
    coverCpp += "static CoverPoints coverPoints;"
    for (info <- covers.keys) {
      coverCpp += s"""
         |extern "C" void ${dpicFuncName(info)}(uint64_t index) {
         |  coverPoints.$info[index] = 1;
         |}
         |""".stripMargin
    }
    for (info <- covers.keys) {
      coverCpp += s"static const char *${info}_NAMES[] = {"
      for ((coverCount, size, modName, varName) <- collectedPoints(info)) {
        val varNameFiltered = varName.replaceAll(""""""", "")
        for (i <- 0 until size) {
          val index = if (size == 1) "" else if (info.isMultibit) s"[$i]" else s" == $i"
          coverCpp += s"""  "$modName.$varNameFiltered$index","""
        }
      }
      coverCpp += s"};"
      coverCpp += ""
    }
    coverCpp += s"FIRRTLCoverPointParam firrtl_cover[${covers.size}] = {"
    for (((info, coverTotal), i) <- covers.zipWithIndex) {
      val isDefaultFeedback = if (i == 0) "true" else "false"
      coverCpp += s"""  { { coverPoints.$info, ${coverTotal}UL, "$info", ${info}_NAMES }, $isDefaultFeedback },"""
    }
    coverCpp += "};"
    coverCpp += ""
    val outputFile = outputDir + "/firrtl-cover.cpp"
    Files.write(Paths.get(outputFile), coverCpp.mkString("\n").getBytes(StandardCharsets.UTF_8))
  }

  private val coverPointBlackboxCache = mutable.Map[(CoverInfo, Int), String]()
  private def coverPointBlackbox(info: CoverInfo, w: Int): String = {
    val hashKey = (info, w)
    if (coverPointBlackboxCache.contains(hashKey)) {
      return coverPointBlackboxCache(hashKey)
    }
    def w_s(_w: Int): String = if (_w > 1) s"[${_w} - 1: 0] " else ""
    val io = extModuleIO(w).map{ case (n, w) => s"input ${w_s(w)}$n" }.mkString(",\n  ")
    val extraCond = if (w > 1 || info.isRaw) "" else " && valid"
    val funcName = dpicFuncName(info)
    val funcCalls = if (info.isMultibit && w > 1) {
      val lines = mutable.ListBuffer.empty[String]
      for (i <- 0 until w) {
        lines += s"      if (valid[$i]) begin"
        lines += s"        $funcName(COVER_INDEX + $i);"
        lines += s"      end"
      }
      lines.mkString("\n")
    } else {
      val extraIndex = if (w > 1 || info.isRaw) " + valid" else ""
      s"$funcName(COVER_INDEX$extraIndex);"
    }
    val module = s"""
       |/*verilator tracing_off*/
       |module ${extModuleName(info, w)}(
       |  $io
       |);
       |  parameter COVER_TOTAL = ${covers(info)};
       |  parameter COVER_INDEX;
       |`ifndef SYNTHESIS
       |  import "DPI-C" function void ${dpicFuncName(info)} (
       |    longint cover_index
       |  );
       |  always @(posedge clock) begin
       |    if (!reset$extraCond) begin
       |      $funcCalls
       |    end
       |  end
       |`endif
       |endmodule
       |""".stripMargin
    coverPointBlackboxCache(hashKey) = module
    module
  }

  def onTopModule(circuitName: CircuitName, top: Module): (Seq[Annotation], Module) = {
    def wiringName(portName: String): String = s"coverage_$portName"
    val globalWiringSink = globalInputs.map { case (modName, portName, _) =>
      SinkAnnotation(ComponentName(portName, ModuleName(modName, circuitName)), wiringName(portName))
    }.toSeq
    val (globalWiringSource, newPorts) = globalInputs.groupBy(x => (x._2, x._3)).keys.map { case (portName, width) =>
      val port = Port(NoInfo, s"coverage_$portName", Input, UIntType(width))
      val anno = SourceAnnotation(ComponentName(port.name, ModuleName(top.name, circuitName)), wiringName(portName))
      (anno, port)
    }.toSeq.unzip
    (globalWiringSink ++ globalWiringSource, top.copy(ports = top.ports ++ newPorts))
  }

  def extraModules: Seq[ExtModule] = coverModules.map(_._1).toSeq
  def extraAnnotations(circuitName: CircuitName): Seq[Annotation] = {
    coverModules.map{ case (coverModule, info, w) =>
      val modName = coverModule.defname
      val body = coverPointBlackbox(info, w)
      BlackBoxInlineAnno(ModuleName(modName, circuitName), s"$modName.v", body)
    }.toSeq
  }
}

class CoverPointTransform extends Transform with DependencyAPIMigration {
  override def prerequisites: Seq[TransformDependency] = firrtl.stage.Forms.LowForm
  override def optionalPrerequisites: Seq[TransformDependency] = firrtl.stage.Forms.LowFormOptimized
  override def optionalPrerequisiteOf: Seq[TransformDependency] = firrtl.stage.Forms.LowEmitters
  override def invalidates(a: Transform): Boolean = a match {
    case _: BlackBoxSourceHelper => true
    case _ => false
  }
  def cleanup: Seq[Transform] = Seq(new WiringTransform, firrtl.transforms.RemoveReset)

  private def onStmt(coverPoints: Map[String, Seq[CoverInfo]], maker: CoverPointMaker, m: DefModule)(stmt: Statement): Statement = {
    stmt match {
      case w: DefWire if coverPoints.contains(w.name) =>
        throw new Exception(s"Does not support DefWire now for ${w.name}")
      case r: DefRegister if coverPoints.contains(r.name) =>
        throw new Exception(s"Does not support DefRegister now for ${r.name}")
      case i: DefInstance if coverPoints.contains(i.name) =>
        throw new Exception(s"Does not support DefInstance now for ${i.name}")
      case m: DefMemory if coverPoints.contains(m.name) =>
        throw new Exception(s"Does not support DefMemory now for ${m.name}")
      case n: DefNode if coverPoints.contains(n.name) =>
        maker.onStmt(n, coverPoints(n.name), m)
      case s => s.mapStmt(onStmt(coverPoints, maker, m))
    }
  }

  override def execute(state: CircuitState): CircuitState = {
    val coverAnnotations = state.annotations.flatMap {
      case CoverPointAnnotation(target: ReferenceTarget, info) => Some((target, info))
      // TODO: implement annotations for InstanceTarget, CircuitTarget, ModuleTarget
      // case CoverPointAnnotation(target: InstanceTarget, info) => Some((target, info))
      // case CoverPointAnnotation(target: CircuitTarget, info) => Some((target, info))
      // case CoverPointAnnotation(target: ModuleTarget, info) => Some((target, info))
      case CoverPointAnnotation(target: Target, _) => throw new Exception(s"Unknown type of target $target")
      case _ => None
    }
    if (coverAnnotations.isEmpty) {
      return state
    }
    val maker = new CoverPointMaker(coverAnnotations.map(_._2))
    val annotatedModules = coverAnnotations.groupBy(_._1.moduleOpt.get)
    val modules = state.circuit.modules.map {
      case m: DefModule if annotatedModules.contains(m.name) =>
        val coverPoints = annotatedModules(m.name).groupBy(_._1.ref).map { case (k, v) => (k, v.map(_._2))}
        m.mapStmt(onStmt(coverPoints, maker, m))
      case m => m
    }
    val (Seq(topModule: Module), otherMods) = modules.partition(_.name == state.circuit.main)
    val (wiringAnno, newTop) = maker.onTopModule(CircuitName(state.circuit.main), topModule)
    maker.writeExtraFiles()
    val extraAnno = maker.extraAnnotations(CircuitName(state.circuit.main))
    val circuit = state.circuit.copy(modules = otherMods ++ maker.extraModules :+ newTop)
    val annotations = state.annotations.filterNot(_.isInstanceOf[CoverPointAnnotation]) ++ wiringAnno ++ extraAnno
    val res = state.copy(circuit = circuit, annotations = annotations)
    cleanup.foldLeft(res) { case (in, xform) => xform.runTransform(in) }
  }
}

object CoverPoint {
  def add(component: InstanceId, info: String): Unit = {
    annotate(new ChiselAnnotation with RunFirrtlTransform {
      def toFirrtl: Annotation = CoverPointAnnotation(component.toTarget, CoverInfo(info))
      def transformClass: Class[CoverPointTransform] = classOf[CoverPointTransform]
    })
  }

  def getTransforms(args: Array[String]): (Array[String], Seq[firrtl.annotations.Annotation]) = {
    val transforms = args.find(_.startsWith("COVER=")).map(t => {
      val covers = t.substring(6).split(",")
      val coverTransforms = scala.collection.mutable.ListBuffer[firrtl.annotations.Annotation]()
      if (covers.nonEmpty) {
        coverTransforms.append(RunFirrtlTransformAnnotation(new NoDedupTransform))
      }
      if (covers.exists(x => !x.endsWith("_old"))) {
        coverTransforms.append(
          RunFirrtlTransformAnnotation(new DontTouchClockAndResetTransform),
          RunFirrtlTransformAnnotation(new CoverPointTransform),
        )
      }
      coverTransforms.appendAll(covers.flatMap {
        case "mux_old" => Seq(
          RunFirrtlTransformAnnotation(new SplitMuxConditions),
          RunFirrtlTransformAnnotation(new ProfilingTransform)
        )
        case "mux" => Seq(
          RunFirrtlTransformAnnotation(new SplitMuxConditions),
          RunFirrtlTransformAnnotation(new MuxCoverTransform),
        )
        case "control_old" => Seq(
          RunFirrtlTransformAnnotation(new ControlRegisterCoverTransform)
        )
        case "control" => Seq(
          RunFirrtlTransformAnnotation(new ControlRegisterCoverTransform),
        )
        case "line" => LineCoverage.annotations.toSeq
        case "fsm" => FsmCoverage.annotations.toSeq
        case "toggle" => ToggleCoverage.registers.toSeq
        case "toggle_full" => ToggleCoverage.all.toSeq
        case "ready_valid" => ReadyValidCoverage.annotations.toSeq
        case _ => Seq()
      })
      coverTransforms.toSeq
    }).getOrElse(Seq())
    (args.filter(_.startsWith("COVER=")), transforms)
  }
}
