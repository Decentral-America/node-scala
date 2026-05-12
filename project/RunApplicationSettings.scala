import sbt.*
import sbt.Keys.*

object RunApplicationSettings extends AutoPlugin {
  override def projectSettings: Seq[Def.Setting[_]] =
    inConfig(Compile)(
      Seq(
        mainClass             := Some("com.decentralchain.Application"),
        discoveredMainClasses := (Compile / mainClass).value.toSeq,
        run / fork            := true
      )
    )
}
