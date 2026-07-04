// Should go before Scala.js
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.8")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "1.0.0-alpha.5"

Seq(
  "com.github.sbt"     % "sbt-git"                  % "2.1.0",
  "com.github.sbt"     % "sbt-native-packager"      % "1.11.7",
  "com.github.sbt"     % "sbt-pgp"                  % "2.3.1",
  "com.github.sbt"     % "sbt-javaagent"            % "0.2.0",
  "org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2",
  "org.scala-js"       % "sbt-scalajs"              % "1.22.0",
  "org.scalameta"      % "sbt-scalafmt"             % "2.6.1",
  "org.scoverage"      % "sbt-scoverage"            % "2.4.4",
  "ch.epfl.scala"      % "sbt-scalafix"             % "0.14.7",
  "com.github.cb372"   % "sbt-explicit-dependencies" % "0.3.1",
  "org.xerial.sbt"     % "sbt-sonatype"             % "3.12.2",
  "pl.project13.scala" % "sbt-jmh"                  % "0.4.8"
).map(addSbtPlugin)

val dockerJavaVersion = "3.7.1"

libraryDependencies ++= Seq(
  "com.fasterxml.jackson.module" %% "jackson-module-scala"              % "2.22.0",
  "org.hjson"                     % "hjson"                             % "3.1.0",
  "org.vafer"                     % "jdeb"                              % "1.14" artifacts Artifact("jdeb", "jar", "jar"),
  "org.slf4j"                     % "jcl-over-slf4j"                    % "2.0.18",
  "com.github.docker-java"        % "docker-java-core"                  % dockerJavaVersion,
  "com.github.docker-java"        % "docker-java-transport-httpclient5" % dockerJavaVersion
)
