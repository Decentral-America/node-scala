import com.typesafe.sbt.SbtNativePackager.Debian

enablePlugins(
  RunApplicationSettings,
  JavaServerAppPackaging,
  UniversalDeployPlugin,
  JDebPackaging,
  SystemdPlugin,
  VersionObject,
  JavaAgent,
  PublishedModule
)

V.scalaPackage := "com.decentralchain"

libraryDependencies ++= Dependencies.node.value

instrumentation := false
debArchitecture := Arm64

javaAgents ++= {
  if (instrumentation.value) {
    Dependencies.kanela
  } else {
    Seq.empty
  }
}

homepage := Some(url("https://decentralchain.io/"))

inConfig(Compile)(
  Seq(
    PB.targets += scalapb.gen(flatPackage = true) -> sourceManaged.value,
    PB.protoSources += PB.externalIncludePath.value,
    PB.generate / includeFilter := { (f: File) =>
      (** / "dcc" / "*.proto").matches(f.toPath)
    },
    PB.deleteTargetDirectory := false
  )
)

// Adds "$lib_dir/*" to app_classpath in the executable file, this is needed for extensions
scriptClasspath += "*"

bashScriptExtraDefines +=
  """# Workaround to ignore the -h option
    |process_args() {
    |  local no_more_snp_opts=0
    |  while [[ $# -gt 0 ]]; do
    |    case "$1" in
    |    --) shift && no_more_snp_opts=1 && break ;;
    |    -no-version-check) no_version_check=1 && shift ;;
    |    -java-home) require_arg path "$1" "$2" && jre=$(eval echo $2) && java_cmd="$jre/bin/java" && shift 2 ;;
    |     -D*|-agentlib*|-agentpath*|-javaagent*|-XX*) addJava "$1" && shift ;;
    |                                             -J*) addJava "${1:2}" && shift ;;
    |                                               *) addResidual "$1" && shift ;;
    |    esac
    |  done
    |
    |  if [[ no_more_snp_opts ]]; then
    |    while [[ $# -gt 0 ]]; do
    |      addResidual "$1" && shift
    |    done
    |  fi
    |
    |  is_function_defined process_my_args && {
    |    myargs=("${residual_args[@]}")
    |    residual_args=()
    |    process_my_args "${myargs[@]}"
    |  }
    |}
    |""".stripMargin

bashScriptExtraDefines += bashScriptEnvConfigLocation.value.fold("")(envFile => s"[[ -f $envFile ]] && . $envFile")

linuxScriptReplacements += ("network" -> network.value.toString)

inConfig(Universal)(
  Seq(
    maintainer  := "decentralchain",
    packageName := s"decentralchain-${version.value}",
    mappings += (baseDirectory.value / s"decentralchain-sample.conf" -> "doc/decentralchain.conf.sample"),
    javaOptions ++= Seq(
      // -J prefix is required by the bash script
      "-J-server",
      "-J-Xmx2g",
      "-J-XX:+ExitOnOutOfMemoryError",
      "-J-XX:+UseG1GC",
      "-J-XX:+ParallelRefProcEnabled",
      "-J-XX:+UseStringDeduplication",
      // JVM default charset for proper and deterministic getBytes behaviour
      "-J-Dfile.encoding=UTF-8",
      "-J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "-J--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED"
    )
  )
)

inConfig(Linux)(
  Seq(
    packageSummary     := "DecentralChain node",
    packageDescription := "DecentralChain node",
    name               := s"decentralchain${network.value.packageSuffix}",
    normalizedName     := name.value,
    packageName        := normalizedName.value
  )
)

def fixScriptName(path: String, name: String, packageName: String): String =
  path.replace(s"/bin/$name", s"/bin/$packageName")

linuxPackageMappings := linuxPackageMappings.value.map { lpm =>
  lpm.copy(mappings = lpm.mappings.map {
    case (file, path) if path.endsWith(s"/bin/${name.value}") => file -> fixScriptName(path, name.value, (Linux / packageName).value)
    case (file, path) if path.endsWith("/conf/application.ini") =>
      val dest = (Debian / target).value / path
      IO.write(
        dest,
        s"""-J-Ddcc.defaults.blockchain.type=${network.value}
           |-J-Ddcc.defaults.directory=/var/lib/${(Linux / packageName).value}
           |-J-Ddcc.defaults.config.directory=/etc/${(Linux / packageName).value}
           |""".stripMargin
      )
      IO.append(dest, IO.readBytes(file))
      dest -> path
    case other => other
  })
}

linuxPackageSymlinks := linuxPackageSymlinks.value.map { lsl =>
  if (lsl.link.endsWith(s"/bin/${name.value}"))
    lsl.copy(
      fixScriptName(lsl.link, name.value, (Linux / packageName).value),
      fixScriptName(lsl.destination, name.value, (Linux / packageName).value)
    )
  else lsl
}

inConfig(Debian)(
  Seq(
    packageArchitecture      := debArchitecture.value.debString,
    maintainer               := "decentralchain",
    packageSource            := sourceDirectory.value / "package",
    linuxStartScriptTemplate := (packageSource.value / "systemd.service").toURI.toURL,
    debianPackageDependencies += "java17-runtime-headless",
    maintainerScripts := maintainerScriptsFromDirectory(packageSource.value / "debian", Seq("postinst", "postrm", "prerm")),
    linuxPackageMappings := {
      val classifier = if (packageArchitecture.value == "amd64") "linux-x86_64" else "linux-aarch_64"
      val platformSpecificMappings = packageMapping(
        (Optional / update).value
          .select(artifactFilter(classifier = classifier))
          .map(f => f -> (defaultLinuxInstallLocation.value + "/" + (Debian / packageName).value + "/lib/software.amazon.cryptools." + f.getName)): _*
      )

      linuxPackageMappings.value.map(m =>
        m.copy(mappings = m.mappings.filterNot { case (f, _) =>
          f.name.contains("AmazonCorretto") || f.name.contains("conscrypt")
        })
      ) :+ platformSpecificMappings
    }
  )
)

V.scalaPackage := "com.decentralchain"
