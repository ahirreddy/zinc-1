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

package sbt.internal.inc.consistent

import sbt.io.{ IO, Using }
import xsbti.compile.analysis.ReadWriteMappers
import xsbti.compile.{ AnalysisContents, AnalysisStore => XAnalysisStore }

import java.io.{ File, FileInputStream, FileOutputStream }
import java.util.Optional
import scala.util.control.Exception.allCatch

object SplitFileAnalysisStore {
  def text(
      depFile: File,
      mainFile: Option[File],
      mappers: ReadWriteMappers,
      reproducible: Boolean = true,
      parallelism: Int = Runtime.getRuntime.availableProcessors()
  ): XAnalysisStore =
    new AStore(
      depFile,
      mainFile,
      new SplitAnalysisFormat(mappers, reproducible),
      SerializerFactory.text,
      parallelism
    )

  def binary(
      depFile: File,
      mainFile: Option[File],
      mappers: ReadWriteMappers,
      reproducible: Boolean,
      parallelism: Int = Runtime.getRuntime.availableProcessors()
  ): XAnalysisStore =
    new AStore(
      depFile,
      mainFile,
      new SplitAnalysisFormat(mappers, reproducible),
      SerializerFactory.binary,
      parallelism
    )

  private def writeFile[S <: Serializer](
      file: File,
      sf: SerializerFactory[S, _],
      parallelism: Int
  )(f: S => Unit): Unit = {
    val tmp = File.createTempFile(file.getName, ".tmp")
    if (!file.getParentFile.exists()) file.getParentFile.mkdirs()
    val fout = new FileOutputStream(tmp)
    try {
      val gout = new ParallelGzipOutputStream(fout, parallelism)
      val ser = sf.serializerFor(gout)
      f(ser)
      gout.close()
    } finally fout.close()
    IO.move(tmp, file)
  }

  private final class AStore[S <: Serializer, D <: Deserializer](
      depFile: File,
      mainFile: Option[File],
      format: SplitAnalysisFormat,
      sf: SerializerFactory[S, D],
      parallelism: Int = Runtime.getRuntime.availableProcessors()
  ) extends XAnalysisStore {

    def set(analysisContents: AnalysisContents): Unit = {
      val analysis = analysisContents.getAnalysis
      val setup = analysisContents.getMiniSetup
      writeFile(depFile, sf, parallelism) { ser =>
        format.writeDep(ser, analysis, setup.storeApis())
      }
      mainFile.foreach { mf =>
        writeFile(mf, sf, parallelism) { ser =>
          format.writeMain(ser, analysis, setup)
        }
      }
    }

    def get(): Optional[AnalysisContents] = {
      import sbt.internal.inc.JavaInterfaceUtil.EnrichOption
      allCatch.opt(unsafeGet()).toOptional
    }

    def unsafeGet(): AnalysisContents = {
      val depAnalysis = Using.gzipInputStream(new FileInputStream(depFile)) { in =>
        val deser = sf.deserializerFor(in)
        format.readDep(deser)
      }
      mainFile match {
        case Some(mf) =>
          Using.gzipInputStream(new FileInputStream(mf)) { in =>
            val deser = sf.deserializerFor(in)
            val (analysis, setup) = format.readMain(deser, depAnalysis)
            AnalysisContents.create(analysis, setup)
          }
        case None =>
          AnalysisContents.create(depAnalysis, SplitAnalysisFormat.emptySetup)
      }
    }
  }
}
