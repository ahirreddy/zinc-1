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

package sbt.inc.consistent

import java.io.File
import java.util.Arrays
import org.scalatest.funsuite.AnyFunSuite
import sbt.internal.inc.consistent.{ ConsistentFileAnalysisStore, SplitFileAnalysisStore }
import sbt.internal.inc.{ Analysis, FileAnalysisStore }
import sbt.io.IO
import xsbti.compile.AnalysisContents
import xsbti.compile.analysis.ReadWriteMappers

class SplitAnalysisFormatIntegrationSuite extends AnyFunSuite {
  val mappers: ReadWriteMappers = ReadWriteMappers.getEmptyMappers
  val data: Seq[File] =
    Seq("compiler.zip", "library.zip", "reflect.zip").map(f => new File("../../../test-data", f))

  def readConsistent(d: File): AnalysisContents = {
    val store = ConsistentFileAnalysisStore.binary(d, mappers)
    val api = store.unsafeGet()
    assert(api.getAnalysis.asInstanceOf[Analysis].apis.internal.head._2.api() != null)
    assert(api.getMiniSetup.storeApis())
    api
  }

  def readLegacy(d: File): AnalysisContents = {
    val store = FileAnalysisStore.binary(d)
    val api = store.unsafeGet()
    assert(api.getAnalysis.asInstanceOf[Analysis].apis.internal.head._2.api() != null)
    assert(api.getMiniSetup.storeApis())
    api
  }

  def writeSplit(
      depName: String,
      mainName: String,
      api: AnalysisContents,
      sort: Boolean = true
  ): (File, File) = {
    val depFile = new File(IO.temporaryDirectory, depName)
    val mainFile = new File(IO.temporaryDirectory, mainName)
    if (depFile.exists()) IO.delete(depFile)
    if (mainFile.exists()) IO.delete(mainFile)
    SplitFileAnalysisStore.binary(depFile, Some(mainFile), mappers, sort).set(api)
    (depFile, mainFile)
  }

  def readSplit(depFile: File, mainFile: File): AnalysisContents = {
    val store = SplitFileAnalysisStore.binary(depFile, Some(mainFile), mappers, reproducible = true)
    val api = store.unsafeGet()
    assert(api.getAnalysis.asInstanceOf[Analysis].apis.internal.head._2.api() != null)
    assert(api.getMiniSetup.storeApis())
    api
  }

  def readSplitDepOnly(depFile: File): AnalysisContents = {
    val store = SplitFileAnalysisStore.binary(depFile, None, mappers, reproducible = true)
    store.unsafeGet()
  }

  test("Consistent output") {
    for (d <- data) {
      assert(d.exists())
      val api = readLegacy(d)
      val (dep1, main1) = writeSplit("sdep1.zip", "smain1.zip", api)
      val (dep2, main2) = writeSplit("sdep2.zip", "smain2.zip", api)
      assert(Arrays.equals(IO.readBytes(dep1), IO.readBytes(dep2)), s"same dep output for $d")
      assert(Arrays.equals(IO.readBytes(main1), IO.readBytes(main2)), s"same main output for $d")
    }
  }

  test("Roundtrip") {
    for (d <- data) {
      assert(d.exists())
      val api = readLegacy(d)
      val (dep1, main1) = writeSplit("sdep1.zip", "smain1.zip", api)
      val api2 = readSplit(dep1, main1)
      val (dep2, main2) = writeSplit("sdep2.zip", "smain2.zip", api2)
      assert(Arrays.equals(IO.readBytes(dep1), IO.readBytes(dep2)), s"same dep output for $d")
      assert(Arrays.equals(IO.readBytes(main1), IO.readBytes(main2)), s"same main output for $d")
    }
  }

  test("Unsorted roundtrip") {
    for (d <- data) {
      assert(d.exists())
      val api = readLegacy(d)
      val (dep1, main1) = writeSplit("sdep1.zip", "smain1.zip", api)
      val api2 = readSplit(dep1, main1)
      val (dep2, main2) = writeSplit("sdep2.zip", "smain2.zip", api2, sort = false)
      val api3 = readSplit(dep2, main2)
      val (dep3, main3) = writeSplit("sdep3.zip", "smain3.zip", api3)
      assert(Arrays.equals(IO.readBytes(dep1), IO.readBytes(dep3)), s"same dep output for $d")
      assert(Arrays.equals(IO.readBytes(main1), IO.readBytes(main3)), s"same main output for $d")
    }
  }

  test("Dep-only read contains expected data") {
    for (d <- data) {
      assert(d.exists())
      val api = readLegacy(d)
      val (dep1, _) = writeSplit("sdep1.zip", "smain1.zip", api)
      val depOnly = readSplitDepOnly(dep1)
      val depAnalysis = depOnly.getAnalysis.asInstanceOf[Analysis]
      val fullAnalysis = api.getAnalysis.asInstanceOf[Analysis]
      assert(depAnalysis.apis.internal.nonEmpty, "dep-only should have internal APIs")
      assert(depAnalysis.apis.internal.size == fullAnalysis.apis.internal.size)
      assert(depAnalysis.relations.productClassName == fullAnalysis.relations.productClassName)
      assert(depAnalysis.apis.external.isEmpty, "dep-only should have no external APIs")
      assert(depAnalysis.stamps.products.isEmpty)
      assert(depAnalysis.stamps.sources.isEmpty)
      assert(depAnalysis.stamps.libraries.isEmpty)
      assert(depAnalysis.infos.allInfos.isEmpty)
    }
  }
}
