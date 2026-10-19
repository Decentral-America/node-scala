package com.decentralchain.settings

import com.decentralchain.common.state.ByteStr

/** Consensus parameters pinned to chains that already have history, keyed by genesis signature.
  * A node loading such a chain gets the pinned value when its config omits the key (or leaves the
  * case-class default); a config that sets a different value fails settings loading, so the node
  * refuses to start before touching state. A stale or partial config can therefore no longer
  * change consensus parameters.
  */
object KnownChainParameters {
  final case class Pinned(name: String, hotStuffEquivocationPenaltyHeight: Int, finalityHardeningHeight: Int)

  val DccTestnetRelaunchGenesis: ByteStr =
    ByteStr.decodeBase58("5be1Fs9oqWGnUt1vVpBDXaJNN583tqic72hFwY9mmMf56n5WevrmwqMzdLSRVdEu7DWGhuQkVL1zhYBn4iuz9DX4").get

  val byGenesisSignature: Map[ByteStr, Pinned] = Map(
    DccTestnetRelaunchGenesis -> Pinned(
      "DCC testnet (genesis 2026-09-04)",
      hotStuffEquivocationPenaltyHeight = 60000,
      finalityHardeningHeight = 90000
    )
  )

  private val Defaults = FunctionalitySettings()

  def enforce(genesisSignature: Option[ByteStr], fs: FunctionalitySettings): Either[String, FunctionalitySettings] =
    genesisSignature.flatMap(byGenesisSignature.get) match {
      case None    => Right(fs)
      case Some(p) =>
        for {
          penalty <- pin(
            "hot-stuff-equivocation-penalty-height",
            fs.hotStuffEquivocationPenaltyHeight,
            Defaults.hotStuffEquivocationPenaltyHeight,
            p.hotStuffEquivocationPenaltyHeight,
            p.name
          )
          hardening <- pin(
            "finality-hardening-height",
            fs.finalityHardeningHeight,
            Defaults.finalityHardeningHeight,
            p.finalityHardeningHeight,
            p.name
          )
        } yield fs.copy(hotStuffEquivocationPenaltyHeight = penalty, finalityHardeningHeight = hardening)
    }

  private def pin(key: String, configured: Int, default: Int, pinned: Int, chain: String): Either[String, Int] =
    if (configured == default || configured == pinned) Right(pinned)
    else Left(s"$key = $configured conflicts with the value $pinned pinned for $chain; remove the key or set it to $pinned")
}
