// Copyright 2021 The Regents of the University of California
// released under BSD 3-Clause License
// author: Kevin Laeufer <laeufer@cs.berkeley.edu>

package sic

import chiseltest.coverage._
import firrtl._
import firrtl.analyses.InstanceKeyGraph
import firrtl.analyses.InstanceKeyGraph.InstanceKey
import firrtl.options.Dependency
import firrtl.passes.InlineInstances
import firrtl.stage.TransformManager.TransformDependency
import logger.LazyLogging
import rfuzz.DoNotProfileModule

import scala.collection.mutable
import scala.util.matching.Regex

/** rfuzz.DoNotProfileModule: Tags a module that should not have any coverage added.
 *  This annotation should be respected by all automated coverage passes.
 */

object Coverage {
  val AllPasses: Seq[TransformDependency] = Seq(
    Dependency(LineCoveragePass), Dependency(ToggleCoveragePass), Dependency(FsmCoveragePass),
  )

  def collectTestCoverage(annos: AnnotationSeq): List[(String, Long)] = {
    annos.collect { case TestCoverage(e) => e } match {
      case Seq(one) => one
      case other    => throw new RuntimeException(s"Expected exactly one TestCoverage annotation, not: $other")
    }
  }

  def collectModuleInstances(annos: AnnotationSeq): List[(String, String)] = {
    annos.collect { case ModuleInstancesAnnotation(e) => e } match {
      case Seq(one) => one
      case other    => throw new RuntimeException(s"Expected exactly one ModuleInstances annotation, not: $other")
    }
  }

  def moduleToInstances(annos: AnnotationSeq): Map[String, List[String]] = {
    collectModuleInstances(annos).groupBy(_._2).map{ case (k,v) => k -> v.map(_._1) }
  }

  def collectModulesToIgnore(state: CircuitState): Set[String] = {
    val main = state.circuit.main
    state.annotations.collect { case DoNotProfileModule(target) if target.circuit.name == main => target.name }.toSet
  }

  def path(prefix: String, suffix: String): String = {
    if (prefix.isEmpty) suffix else prefix + "." + suffix
  }

  type Lines = List[(String, List[Int])]
  private val chiselFileInfo: Regex = raw"\s*([^\.]+\.\w+) (\d+):(\d+)".r

  def parseFileInfo(i: ir.FileInfo): Seq[(String, Int)] = {
    chiselFileInfo.findAllIn(i.unescaped).map {
      case chiselFileInfo(filename, line, col) => (filename, line.toInt)
    }.toSeq
  }

  def infosToLines(infos: Seq[ir.Info]): Lines = {
    val parsed = findFileInfos(infos).flatMap(parseFileInfo)
    val byFile = parsed.groupBy(_._1).toList.sortBy(_._1)
    byFile.map { case (filename, e) => filename -> e.map(_._2).toSet.toList.sorted }
  }

  def findFileInfos(infos: Seq[ir.Info]): Seq[ir.FileInfo] = infos.flatMap(findFileInfos)
  def findFileInfos(info:  ir.Info): Seq[ir.FileInfo] = info match {
    case ir.MultiInfo(infos) => findFileInfos(infos)
    case f: ir.FileInfo => List(f)
    case _ => List()
  }
}

/** Represents a Scala code base. */
class CodeBase(root: os.Path) extends LazyLogging {
  require(os.exists(root), s"Could not find root directory: $root")
  require(os.isDir(root), s"Is not a directory: $root")

  val index = CodeBase.index(root)
  private val duplicates = index.filter(_._2.size > 1)

  def warnAboutDuplicates(): Unit = {
    if (duplicates.nonEmpty) {
      val msgs = duplicates.flatMap { case (key, values) =>
        Seq(s"Multiple files map to key: $key") ++
          values.map(v => s"  - $v")
      }

      val msg = Seq(s"In code base: $root") ++ msgs
      logger.warn(msg.mkString("\n"))
    }
  }

  val duplicateKeys: List[String] = duplicates.keys.toList
  def isDuplicate(key:  String): Boolean = getDuplicate(key).isDefined
  def getDuplicate(key: String): Option[List[os.RelPath]] = duplicates.get(key)

  /** returns None if the key is not unique */
  def getLine(key: String, line: Int): Option[String] = {
    require(line > 0)
    getSource(key).map(_(line - 1))
  }

  private val sourceCache = mutable.HashMap[os.RelPath, IndexedSeq[String]]()
  def getSource(key: String): Option[IndexedSeq[String]] = getFilePath(key).map { rel =>
    sourceCache.getOrElseUpdate(rel, os.read.lines(root / rel))
  }

  /** returns None if the key is not unique */
  private def getFilePath(key: String): Option[os.RelPath] = index.get(key) match {
    case Some(List(one)) => Some(one)
    case _               => None
  }

}

object CodeBase {

  /** finds all source files in the path and maps them by their filename */
  private def index(root: os.Path, exts: Set[String] = Set("scala")): Map[String, List[os.RelPath]] = {
    val i = mutable.HashMap[String, List[os.RelPath]]()
    index(root, root, exts, i)
    i.toMap
  }

  private def index(root: os.Path, dir: os.Path, exts: Set[String], i: mutable.HashMap[String, List[os.RelPath]]): Unit = {
    val stream = os.walk.stream(dir)
    stream.foreach { f: os.Path =>
      if (exts.contains(f.ext)) {
        val key = f.last
        val old = i.getOrElse(key, List())
        val relative = f.relativeTo(root)
        i(key) = relative +: old
      }
    }
  }
}

/** this is a copy of the upstream version from chiseltest with some dependencies changed */
object ModuleInstancesPass extends Transform with DependencyAPIMigration {
  override def prerequisites: Seq[TransformDependency] = Seq()
  // we needs to run *after* any transform that changes the hierarchy
  override def optionalPrerequisites: Seq[TransformDependency] = Seq(Dependency[InlineInstances])
  override def invalidates(a: Transform): Boolean = false

  override protected def execute(state: CircuitState): CircuitState = {
    val children = InstanceKeyGraph(state.circuit).getChildInstances.toMap
    val topInstance = InstanceKey("", state.circuit.main)
    val topChildren = children(topInstance.module)
    val instances = topInstance +: topChildren.flatMap(onInstance("", _, children))
    val instanceToModule = instances.toList.map(i => i.name -> i.module)
    val anno = ModuleInstancesAnnotation(instanceToModule)
    state.copy(annotations = anno +: state.annotations)
  }

  /** expands the instance name to its complete path (relative to the main module) */
  private def onInstance(
    prefix:   String,
    inst:     InstanceKey,
    children: Map[String, Seq[InstanceKey]]
  ): Seq[InstanceKey] = {
    val ii = InstanceKey(prefix + inst.name, inst.module)
    val cc = children(ii.module).flatMap(onInstance(ii.name + ".", _, children))
    ii +: cc
  }
}