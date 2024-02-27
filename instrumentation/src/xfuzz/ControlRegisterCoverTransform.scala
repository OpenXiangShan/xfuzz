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

import difuzz.{GraphLedger, InstrCov, ModuleInfo}
import firrtl._
import firrtl.annotations.ModuleName
import firrtl.ir.ExtModule
import firrtl.options.Dependency
import firrtl.stage.TransformManager.TransformDependency
import rfuzz.DoNotProfileModule

class ControlRegisterCoverTransform extends Transform with DependencyAPIMigration {
  override def prerequisites: Seq[TransformDependency] = firrtl.stage.Forms.LowForm
  override def optionalPrerequisites: Seq[TransformDependency] = firrtl.stage.Forms.LowFormOptimized
  override def optionalPrerequisiteOf: Seq[TransformDependency] = Seq(Dependency[CoverPointTransform])
  override def invalidates(a: Transform): Boolean = false

  override def execute(state: CircuitState): CircuitState = {
    val dontProfile = state.annotations
      .collect { case DoNotProfileModule(ModuleName(m, _)) => m }
      .toSet
    val extModules = state.circuit.modules.filter(_.isInstanceOf[ExtModule]).map(_.name)
    val (mods, annos) = state.circuit.modules.map {
      case m: firrtl.ir.Module if !dontProfile(m.name) =>
        val ledger = new GraphLedger(m).parseModule
        val cov = new InstrCov(m, ModuleInfo(m, ledger), extModules)
        val (mod, annos) = cov.annotate(state.circuit.main)
        (mod, annos)
      case mod => (mod, Seq())
    }.unzip
    val circuitx = state.circuit.copy(modules = mods)
    val annosx = state.annotations ++ annos.flatten
    state.copy(circuit = circuitx, annotations = annosx)
  }
}
