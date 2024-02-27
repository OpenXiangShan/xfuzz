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

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.transforms.DontTouchAnnotation
import rfuzz.NoDedupTransform

import scala.collection.mutable.ListBuffer

// mark clock and reset as dontTouch
class DontTouchClockAndResetTransform extends Transform with DependencyAPIMigration {
  override def invalidates(a: Transform): Boolean = a match {
    case _: NoDedupTransform => false
    case _ => true
  }

  val dontTouchAnnos = ListBuffer.empty[Annotation]
  private def dontTouchPorts(cond: Seq[Port => Boolean], circuit: String)(mod: DefModule): Unit = {
    for (port <- mod.ports.filter(p => cond.exists(_(p)))) {
      val target = ReferenceTarget(circuit, mod.name, Seq(), port.name, Seq())
      dontTouchAnnos += DontTouchAnnotation(target)
    }
  }

  private val isClock = (port: Port) => port.tpe == ClockType
  private val isReset = (port: Port) => port.tpe == ResetType
  def execute(state: CircuitState): CircuitState = {
    val clockAndReset = Seq(isClock, isReset)
    state.circuit.foreachModule(dontTouchPorts(clockAndReset, state.circuit.main))
    state.copy(annotations = state.annotations ++ dontTouchAnnos)
  }
}
