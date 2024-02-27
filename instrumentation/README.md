# Chisel Coverage Instrumentatio Transforms

This directory contains some FIRRTL transforms for coverage instrumentation of Chisel designs.
The code is migrated from the corresponding GitHub repositories.
The license is reserved by the original repositories and authors.

## Example Usage

Refer to the [rocket-chip](https://github.com/OpenXiangShan/rocket-chip/tree/dev-difftest) project.

- In `build.sc`:

```scala
trait CcoverModule extends SbtModule
    with HasChisel
    with Cross.Module[String] {

  def scalaVersion: T[String] = T(v.scala)

  def sourceRoot = T.sources { T.workspace / "ccover" / "instrumentation" / "src" }

  private def getSources(p: PathRef) = if (os.exists(p.path)) os.walk(p.path) else Seq()

  def allSources = T { sourceRoot().flatMap(getSources).map(PathRef(_)) }

  def chiselModule: Option[ScalaModule] = None

  def chiselPluginJar: T[Option[PathRef]] = None

  def chiselIvy = Some(v.chiselCrossVersions(crossValue)._1)

  def chiselPluginIvy = Some(v.chiselCrossVersions(crossValue)._2)

  def ivyDeps = super.ivyDeps() ++ Agg(ivy"edu.berkeley.cs::chiseltest:0.6.2")

}

object ccover extends Cross[CcoverModule](v.chiselCrossVersions.keys.toSeq)
```

- In your top-level Chisel generator class:

```diff
diff --git a/generator/chisel3/src/main/scala/FuzzTop.scala b/generator/chisel3/src/main/scala/FuzzTop.scala
index 983345472..2d94cb533 100644
--- a/generator/chisel3/src/main/scala/FuzzTop.scala
+++ b/generator/chisel3/src/main/scala/FuzzTop.scala
@@ -3,6 +3,7 @@ package freechips.rocketchip.system
 import chisel3.stage.{ChiselCli, ChiselGeneratorAnnotation, ChiselStage}
 import firrtl.options.Shell
 import firrtl.stage.FirrtlCli
+import xfuzz.CoverPoint
 
 class FuzzStage extends ChiselStage {
   override val shell: Shell = new Shell("rocket-chip")
@@ -16,6 +17,6 @@ object FuzzMain {
       ChiselGeneratorAnnotation(() => {
         freechips.rocketchip.diplomacy.DisableMonitors(p => new SimTop()(p))(new FuzzConfig)
       })
-    ))
+    ) ++ CoverPoint.getTransforms(args)._2)
   }
 }
```
