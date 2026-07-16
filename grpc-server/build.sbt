import sbt.nio.file.FileAttributes

name := "dcc-grpc-server"

libraryDependencies ++= Dependencies.grpc

extensionClasses ++= Seq(
  "com.decentralchain.api.grpc.GRPCServerExtension",
  "com.decentralchain.events.BlockchainUpdates"
)

inConfig(Compile)(
  Seq(
    Compile / PB.protoSources   := Seq(PB.externalIncludePath.value),
    PB.generate / includeFilter := new SimpleFileFilter((f: File) =>
      ((** / "dcc" / "node" / "grpc" / ** / "*.proto") || (** / "dcc" / "events" / ** / "*.proto"))
        .accept(f.toPath, FileAttributes(f.toPath).getOrElse(FileAttributes.NonExistent))
    ),
    PB.targets += scalapb.gen(flatPackage = true) -> sourceManaged.value
  )
)

// Matcher depends on this artifact via % Provided — must be publishable.
publish / skip := false

enablePlugins(RunApplicationSettings, ExtensionPackaging)
Universal / maintainer     := "com.decentralchain"
Debian / debianControlFile := {
  val generatedFile = (Debian / debianControlFile).value
  IO.append(
    generatedFile,
    s"""Conflicts: grpc-server${network.value.packageSuffix}
       |Replaces: grpc-server${network.value.packageSuffix}
       |""".stripMargin
  )
  generatedFile
}
