/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt.inc

import sbt.internal.inc._
import sbt.io.IO

class MacroInvalidationSpec extends BaseCompilerSpec {

  /**
   * Create a two-project setup where sub2 depends on sub1, with the analysisForCp
   * correctly wired so that sub2's PerClasspathEntryLookup can find sub1's analysis.
   *
   * The default VirtualSubproject.dependsOn uses each subproject's own converter to
   * create the analysisForCp keys, which causes a mismatch because the classpath entries
   * are created using the dependent project's converter. We fix this by constructing the
   * analysisForCp using the dependent project's converter for all entries.
   */
  private def setupDependentProjects(
      sub1: VirtualSubproject,
      sub2: VirtualSubproject
  ): (ProjectSetup, ProjectSetup) = {
    val sub1Setup = sub1.setup

    val analysisForCp2 = Map(
      sub2.toVf(sub2.classesDir) -> sub2.analysisPath,
      sub2.toVf(sub2.earlyOutput) -> sub2.earlyAnalysisPath,
      sub2.toVf(sub1.classesDir) -> sub1.analysisPath,
      sub2.toVf(sub1.earlyOutput) -> sub1.earlyAnalysisPath,
    )
    val cp2 = List(sub2.earlyOutput, sub1.classesDir)
    val sub2Setup = ProjectSetup(sub2, Map.empty, Nil, analysisForCp2, overrideCp = Some(cp2))

    (sub1Setup, sub2Setup)
  }

  // The macro definition and implementation live in sub1 (the provider). The `macro` keyword
  // in the def causes the compiler to set hasMacro=true on MyMacros, which is what triggers
  // the macro-specific code path in detectAPIChanges for external dependencies.
  private val macroProviderSrc = StringVirtualFile(
    "src/MyMacros.scala",
    """package foo
      |import scala.language.experimental.macros
      |import scala.reflect.macros.blackbox.Context
      |object MyMacros {
      |  def noop(arg: Int): Int = macro MyMacros.noopImpl
      |  def noopImpl(c: Context)(arg: c.Expr[Int]): c.Expr[Int] = arg
      |}
      |""".stripMargin
  )

  private val macroUser = StringVirtualFile(
    "src/MacroUser.scala",
    """class MacroUser {
      |  val x = foo.MyMacros.noop(42)
      |}
      |""".stripMargin
  )

  private val plainClass = StringVirtualFile(
    "src/PlainClass.scala",
    """class PlainClass { val z = 1 }
      |""".stripMargin
  )

  "incremental compiler" should "recompile macro users when external dependency changes" in {
    IO.withTemporaryDirectory { tempDir =>
      val sub1 = VirtualSubproject(tempDir.toPath / "sub1")
      val sub2 = VirtualSubproject(tempDir.toPath / "sub2")
      val (sub1Setup, sub2Setup) = setupDependentProjects(sub1, sub2)

      val c1 = sub1Setup.createCompiler()
      val c2 = sub2Setup.createCompiler()

      try {
        val helperV1 = StringVirtualFile(
          "src/Helper.scala",
          """package foo
            |object Helper { val x = 1 }
            |""".stripMargin
        )
        val helperV2 = StringVirtualFile(
          "src/Helper.scala",
          """package foo
            |object Helper { val x = 1; val y = 2 }
            |""".stripMargin
        )
        // HelperUser depends on foo.Helper so that sub2 tracks it as an external dep.
        // When Helper changes, this triggers API change detection for sub2's externals,
        // which in turn should also flag macro classes for re-expansion.
        val helperUser = StringVirtualFile(
          "src/HelperUser.scala",
          """class HelperUser { val v = foo.Helper.x }
            |""".stripMargin
        )

        c1.compile(macroProviderSrc, helperV1)
        val result1 = c2.compile(macroUser, helperUser)

        // Change a class in the upstream project and recompile it
        c1.compile(macroProviderSrc, helperV2)

        // Recompile the downstream project — macro user should be recompiled
        // because an external dependency changed (even though the macro's own API
        // didn't change, its expansion could depend on the changed code)
        val result2 = c2.compile(macroUser, helperUser)
        val recompiledClasses = recompiled(result1, result2)
        assert(
          recompiledClasses.contains("MacroUser"),
          s"MacroUser should be recompiled when external dependency changes, but recompiled = $recompiledClasses"
        )
      } finally {
        c1.close()
        c2.close()
      }
    }
  }

  it should "not recompile macro users when no external dependency changes" in {
    IO.withTemporaryDirectory { tempDir =>
      val sub1 = VirtualSubproject(tempDir.toPath / "sub1")
      val sub2 = VirtualSubproject(tempDir.toPath / "sub2")
      val (sub1Setup, sub2Setup) = setupDependentProjects(sub1, sub2)

      val c1 = sub1Setup.createCompiler()
      val c2 = sub2Setup.createCompiler()

      try {
        // Initial compilation of both projects
        c1.compile(macroProviderSrc)
        val result1 = c2.compile(macroUser, plainClass)

        // Recompile the downstream project with no upstream changes
        val result2 = c2.compile(macroUser, plainClass)
        val recompiledClasses = recompiled(result1, result2)
        assert(
          !recompiledClasses.contains("MacroUser"),
          s"MacroUser should NOT be recompiled when no external dependency changes, but recompiled = $recompiledClasses"
        )
      } finally {
        c1.close()
        c2.close()
      }
    }
  }
}
