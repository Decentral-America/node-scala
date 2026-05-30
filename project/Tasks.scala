import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, DefaultScalaModule}
import org.hjson.JsonValue
import sbt.Keys.{baseDirectory, sourceManaged, target}
import sbt.{Def, IO, *}

import java.io.File
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters.*
import complete.DefaultParsers.*

object Tasks {
  val mapper = new ObjectMapper() with ClassTagExtensions
  mapper.registerModule(DefaultScalaModule)

  lazy val listComplexFunctions = Def.inputTask {
    val version = (' ' ~> NatBasic).parsed
    val baseLangDir = baseDirectory.value.getParentFile.getAbsolutePath

    val complexFuncs = for {
      path <- Files.list(Paths.get(s"$baseLangDir/doc/v$version/funcs")).iterator().asScala
      json = JsonValue.readHjson(Files.newBufferedReader(path)).asObject().toString
      func <- mapper.readValue[Map[String, List[FuncSourceData]]](json).head._2
      if func.complexity > 1
    } yield s"${func.name};${func.complexity};${func.params.mkString(",")}"


    val targetFile = ((Compile / target).value / s"complex-functions-v$version.csv")
    IO.write(targetFile, complexFuncs.mkString("\n").getBytes("utf-8"))
    targetFile
  }

  lazy val docSource = Def.task {
    val baseLangDir = baseDirectory.value.getParentFile.getAbsolutePath

    def toMapChecked[K, V](data: Seq[V], key: V => K): Map[K, V] =
      data.distinct
        .groupBy(key)
        .ensuring(
          _.forall { case (_, v) =>
            if (v.size == 1) true
            else {
              println(v)
              false
            }
          },
          "Duplicate detected"
        )
        .mapValues(_.head)

    def str(s: String): String = "\"" + s + "\""

    def tupleStr(l: Seq[String]): String = l.mkString("(", ", ", ")")

    def listStr(l: Seq[String]): String = "List" + tupleStr(l)

    def mapStr(kv: Map[Seq[String], Seq[String]]): String = {
      val inner = kv
        .map { case (k, v) => Seq(tupleStr(k), tupleStr(v)) }
        .map(tupleStr)
      "Map" + tupleStr(inner.toSeq)
    }

    def sumMapStr(m1: String, m2: String): String = s"$m1 ++ $m2"

    def kvStr[K, V](
        seq: Seq[V],
        key: V => K,
        keyStr: V => Seq[String],
        valueStr: V => Seq[String]
    ): String =
      mapStr(
        toMapChecked(seq, key).map { case (_, v) => (keyStr(v), valueStr(v)) }
      )

    def buildVarsStr(vars: Seq[VarSourceData], version: Int): String =
      kvStr[String, VarSourceData](
        vars,
        _.name,
        v => Seq(str(v.name), version.toString),
        v => Seq(str(v.doc))
      )

    def buildFuncsStr(funcs: Seq[FuncSourceData], version: Int): String =
      kvStr[(String, List[String]), FuncSourceData](
        funcs,
        f => (f.name, f.params),
        f => Seq(str(f.name), listStr(f.params.map(str)), version.toString),
        f => Seq(str(f.doc), listStr(f.paramsDoc.map(str)), f.complexity.toString)
      )

    def buildTypesStr(vars: Seq[TypeSourceData], ver: String): String =
      kvStr[String, TypeSourceData](
        vars,
        _.name,
        v => Seq(str(v.name), ver),
        v => Seq(listStr(v.fields.map(f => tupleStr(Seq(str(f.name), str(f.`type`))))))
      )

    def readV1V2Data(): (String, String) =
      Seq(1, 2)
        .map { version =>
          val DocSourceData(vars, funcs) = mapper.readValue[DocSourceData](new File(s"$baseLangDir/doc/v$version/data.json"))
          val varDataStr                 = buildVarsStr(vars, version)
          val funcDataStr                = buildFuncsStr(funcs, version)
          (varDataStr, funcDataStr)
        }
        .reduce { (a, b) =>
          val (v1, f1) = a
          val (v2, f2) = b
          (
            sumMapStr(v1, v2),
            sumMapStr(f1, f2)
          )
        }

    def buildCategorizedFuncsStr(funcs: Seq[(FuncSourceData, String)], version: Int): String =
      kvStr[(String, List[String]), (FuncSourceData, String)](
        funcs,
        f => (f._1.name, f._1.params),
        f => Seq(str(f._1.name), listStr(f._1.params.map(str)), version.toString),
        f =>
          Seq(
            str(f._1.doc.replace("\n", "\\n")),
            listStr(f._1.paramsDoc.map(str).map(_.replace("\n", "\\n"))),
            str(f._2),
            f._1.complexity.toString
          )
      )

    def readFuncs(version: Int): String = {
      val funcs = for {
        path <- Files.list(Paths.get(s"$baseLangDir/doc/v$version/funcs")).iterator.asScala
        json = JsonValue.readHjson(Files.newBufferedReader(path)).asObject().toString
        funcs <- mapper.readValue[Map[String, List[FuncSourceData]]](json).head._2
        category = path.getName(path.getNameCount - 1).toString.split('.').head
      } yield (funcs, category)

      // Split into separate private lazy vals to avoid JVM 64KB method limit.
      // Scoverage instrumentation inflates bytecode ~5x per lazy val init method.
      val allFuncs = funcs.toSeq
      val chunkSize = 10
      if (allFuncs.size <= chunkSize) {
        buildCategorizedFuncsStr(allFuncs, version)
      } else {
        allFuncs.grouped(chunkSize).zipWithIndex.map { case (chunk, _) =>
          buildCategorizedFuncsStr(chunk, version)
        }.mkString(" ++ ")
      }
    }

    /** Produces multiple private lazy val definitions for a single RIDE version,
      * each containing at most `chunkSize` function entries. Returns a tuple:
      * (definitions: String, reference expression: String).
      */
    def readFuncsSplit(version: Int): (String, String) = {
      val funcs = for {
        path <- Files.list(Paths.get(s"$baseLangDir/doc/v$version/funcs")).iterator.asScala
        json = JsonValue.readHjson(Files.newBufferedReader(path)).asObject().toString
        funcs <- mapper.readValue[Map[String, List[FuncSourceData]]](json).head._2
        category = path.getName(path.getNameCount - 1).toString.split('.').head
      } yield (funcs, category)

      val allFuncs = funcs.toSeq
      val chunkSize = 10
      val chunks = allFuncs.grouped(chunkSize).toSeq

      if (chunks.size <= 1) {
        val expr = buildCategorizedFuncsStr(allFuncs, version)
        (s"  lazy val funcsV$version = $expr", s"funcsV$version")
      } else {
        val defs = chunks.zipWithIndex.map { case (chunk, idx) =>
          s"  private lazy val funcsV${version}_$idx = ${buildCategorizedFuncsStr(chunk, version)}"
        }.mkString("\n")
        val refs = chunks.indices.map(idx => s"funcsV${version}_$idx").mkString(" ++ ")
        val mainDef = s"$defs\n  lazy val funcsV$version = $refs"
        (mainDef, s"funcsV$version")
      }
    }

    def readVars(version: Int): String = {
      val vars = mapper.readValue[Map[String, List[VarSourceData]]](new File(s"$baseLangDir/doc/v$version/vars.json")).head._2
      buildVarsStr(vars, version)
    }

    def readTypeData(ver: String): String = {
      val typesJson = JsonValue.readHjson(Files.newBufferedReader(Paths.get(s"$baseLangDir/doc/v$ver/types.hjson"))).asObject().toString
      val types     = mapper.readValue[Map[String, List[TypeSourceData]]](typesJson).head._2
      buildTypesStr(types, ver)
    }

    val docFolderR = "^v(\\d+)$".r
    val currentRideVersion =
      new File(s"$baseLangDir/doc")
        .listFiles()
        .map(_.name)
        .collect { case docFolderR(version) => version.toInt }
        .max

    val (v1V2Vars, v1V2Funcs) = readV1V2Data()
    val funcSplits            = (3 to currentRideVersion).map(v => readFuncsSplit(v))
    val fromV3FuncDefs        = funcSplits.map(_._1).mkString("\n")
    val fromV3VarDefs         = (3 to currentRideVersion).map(v => s"  lazy val varsV$v = ${readVars(v)}").mkString("\n")
    val fromV3Vars            = (3 to currentRideVersion).map(v => s"varsV$v").mkString(" ++ ")
    val fromV3Funcs           = funcSplits.map(_._2).mkString(" ++ ")

    // Split typeData into per-version private lazy vals to avoid 64KB method limit
    val typeDataDefs = (1 to currentRideVersion).map(v => s"  private lazy val typeDataV$v = ${readTypeData(v.toString)}").mkString("\n")
    val typeDataRef  = (1 to currentRideVersion).map(v => s"typeDataV$v").mkString(" ++ ")

    val sourceStr =
      s"""
         | package com.decentralchain
         |
         | object DocSource {
         |   private val regex = "\\\\[(.+?)\\\\]\\\\(.+?\\\\)".r
         |
         |   $fromV3FuncDefs
         |   $fromV3VarDefs
         |   $typeDataDefs
         |
         |   lazy val typeData = $typeDataRef
         |   lazy val varData  = $v1V2Vars ++ $fromV3Vars
         |   lazy val funcData = $v1V2Funcs ++ ($fromV3Funcs).view.mapValues(v => (regex.replaceAllIn(v._1, _.group(1)), v._2, v._4))
         | }
      """.stripMargin

    val rawDocFile = (Compile / sourceManaged).value / "com" / "decentralchain" / "DocSource.scala"

    IO.write(rawDocFile, sourceStr)
    Seq(rawDocFile)
  }
}
