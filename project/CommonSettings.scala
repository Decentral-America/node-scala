import sbt.*

object CommonSettings extends AutoPlugin {
  object autoImport extends CommonKeys

  override def trigger: PluginTrigger = allRequirements

  // These options doesn't work for ScalaJS
  override def projectSettings: Seq[Def.Setting[?]] = Seq()
}

sealed abstract class DebArchitecture(val debString: String, val commonName: String)
object Amd64 extends DebArchitecture("amd64", "x86_64")
object Arm64 extends DebArchitecture("arm64", "aarch64")

trait CommonKeys {
  val network         = settingKey[Network]("The network for artifacts")
  val packageSource   = settingKey[File]("Additional files for DEB")
  val instrumentation = settingKey[Boolean]("Include kanela java agent in start script")
  val debArchitecture = settingKey[DebArchitecture]("DEB package architecture")
}
