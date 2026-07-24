import sbt.*
import sbt.Keys.*

import scala.sys.process.*

enablePlugins(IntegrationTestsPlugin)

description := "NODE integration tests"
libraryDependencies ++= Dependencies.it
// ToxiProxy-based fault injection (degraded-link chaos testing), ported from matcher's proven
// dex-it-common/HasToxiProxy pattern -- see ToxiProxyHarness.scala.
libraryDependencies ++= Seq(
  "com.dimafeng"       %% "testcontainers-scala"      % "0.44.1" % Test,
  "org.testcontainers"  % "testcontainers-toxiproxy"  % "2.0.5"  % Test
)

val docker = taskKey[Unit]("Build docker image for integration tests")
docker := {
  val log = streams.value.log

  val cwd   = baseDirectory.value.getParentFile / "docker"
  val image = "com.decentralchain/node-it:latest"

  val cmd = Seq("docker", "build", "-t", image, ".")
  log.info(s"Running `${cmd.mkString(" ")}` from $cwd")

  val processLogger = ProcessLogger(
    (out: String) => log.info(out),
    (err: String) => log.info(err) // Redirect STDERR to info
  )

  val exit = Process(cmd, cwd).!(processLogger)
  if (exit != 0) sys.error(s"Docker build failed with exit code $exit")
}

val buildTarballsForDocker = taskKey[Unit]("build all packages")

// To solve "Error response from daemon: No such image: " see:
// https://github.com/marcus-drake/sbt-docker/issues/133#issuecomment-2718354260
docker := docker.dependsOn(LocalProject("dcc-node") / buildTarballsForDocker).value
