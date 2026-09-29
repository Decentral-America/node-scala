// Should go before Scala.js
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.8")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "1.0.0-alpha.6"

Seq(
  "com.github.sbt"     % "sbt-git"                   % "2.2.0",
  "com.github.sbt"     % "sbt-native-packager"       % "1.12.0",
  "com.github.sbt"     % "sbt-pgp"                   % "2.3.2",
  "com.github.sbt"     % "sbt-javaagent"             % "0.3.0",
  "org.portable-scala" % "sbt-scalajs-crossproject"  % "1.4.0",
  "org.scala-js"       % "sbt-scalajs"               % "1.22.0",
  "org.scalameta"      % "sbt-scalafmt"              % "2.6.2",
  "org.scoverage"      % "sbt-scoverage"             % "2.4.4",
  "ch.epfl.scala"      % "sbt-scalafix"              % "0.14.9",
  "com.github.cb372"   % "sbt-explicit-dependencies" % "0.3.1",
  "org.xerial.sbt"     % "sbt-sonatype"              % "3.12.2",
  "pl.project13.scala" % "sbt-jmh"                   % "0.4.8"
).map(addSbtPlugin)

val dockerJavaVersion = "3.7.1"

libraryDependencies ++= Seq(
  "com.fasterxml.jackson.module" %% "jackson-module-scala"              % "2.22.3.1",
  "org.hjson"                     % "hjson"                             % "3.1.0",
  "org.vafer"                     % "jdeb"                              % "1.14" artifacts Artifact("jdeb", "jar", "jar"),
  "org.slf4j"                     % "jcl-over-slf4j"                    % "2.0.20",
  "com.github.docker-java"        % "docker-java-core"                  % dockerJavaVersion,
  "com.github.docker-java"        % "docker-java-transport-httpclient5" % dockerJavaVersion
)
