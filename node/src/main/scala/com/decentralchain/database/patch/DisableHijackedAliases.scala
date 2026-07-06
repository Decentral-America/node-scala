package com.decentralchain.database.patch

import com.decentralchain.account.Alias
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.database.{Keys, RW}
import com.decentralchain.state.patch.PatchDataLoader
import com.decentralchain.state.Height

case object DisableHijackedAliases extends PatchDataLoader {
  val height: Height = Height(0)

  def apply(rw: RW): Set[Alias] = {
    val aliases = readPatchData[Set[String]]().map(Alias.create(_).explicitGet())
    rw.put(Keys.disabledAliases, aliases)
    aliases
  }

  def revert(rw: RW): Set[Alias] = {
    rw.put(Keys.disabledAliases, Set.empty[Alias])
    Set.empty[Alias]
  }
}
