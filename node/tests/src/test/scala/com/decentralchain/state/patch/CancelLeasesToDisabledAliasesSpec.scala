package com.decentralchain.state.patch

import com.decentralchain.account.PublicKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.settings.{BlockchainSettings, DCCSettings}
import com.decentralchain.state.{Blockchain, Height}
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.transaction.TxHelpers
import org.scalamock.scalatest.MockFactory

// DCC's own chain has no CancelLeasesToDisabledAliases-<chainId>.json patch data (unlike the legacy
// Waves 'W'/'T' networks, which had historical leases to since-disabled aliases to cancel): the resource
// file for DCC's chain id is intentionally empty. These tests exercise the patch mechanism's no-op path
// on the current chain instead of mutating the global AddressScheme singleton to impersonate 'W' — doing
// so is unsafe under sbt's parallel suite execution, since other concurrently-running suites read the
// same mutable AddressScheme.current.
class CancelLeasesToDisabledAliasesSpec extends FlatSpec with WithDomain with MockFactory {
  val MainnetSettings: DCCSettings = {
    import SettingsFromDefaultConfig.blockchainSettings.functionalitySettings as fs
    SettingsFromDefaultConfig.copy(
      blockchainSettings = SettingsFromDefaultConfig.blockchainSettings.copy(
        functionalitySettings = fs.copy(preActivatedFeatures =
          fs.preActivatedFeatures ++ Map(
            BlockchainFeatures.NG.id               -> 0,
            BlockchainFeatures.SmartAccounts.id    -> 0,
            BlockchainFeatures.SynchronousCalls.id -> 2
          )
        )
      )
    )
  }

  "CancelLeasesToDisabledAliases" should "be a no-op with no patch data for the current chain" in
    withDomain(MainnetSettings, AddrWithBalance.enoughBalances(TxHelpers.defaultSigner)) { d =>
      testLeaseBalance(d).out shouldBe 0L

      d.appendKeyBlock()
      testLeaseBalance(d).out shouldBe 0L

      d.appendMicroBlock(TxHelpers.transfer())
      d.appendMicroBlock(TxHelpers.transfer())
      d.appendMicroBlock(TxHelpers.transfer())
      d.appendKeyBlock()
      testLeaseBalance(d).out shouldBe 0L
    }

  it should "be a no-op on extension apply with no patch data for the current chain" in
    withDomain(MainnetSettings, AddrWithBalance.enoughBalances(TxHelpers.defaultSigner)) { d =>
      testLeaseBalance(d).out shouldBe 0L
      d.appendBlock()
      testLeaseBalance(d).out shouldBe 0L
      d.appendBlock()
      testLeaseBalance(d).out shouldBe 0L
      d.appendBlock()
      testLeaseBalance(d).out shouldBe 0L
    }

  private def testLeaseBalance(d: Domain) = {
    d.blockchain.leaseBalance(PublicKey(ByteStr(Base58.decode("6NxhjzayDTd52MJL2r6XupGDb7E1xQW7QppSPqo63gsx"))).toAddress)
  }

  // Regression test for the 95fc1cd4f8 network-filter inversion (see
  // docs/consensus-divergences-from-upstream.md §5 and
  // CONSENSUS-BUG-INVESTIGATION-REFERENCE.md §5): CancelLeasesToDisabledAliases's `networks` set
  // is empty (Set.empty), which under the old `networks.isEmpty || networks.contains(...)` logic
  // meant "applies to every network" instead of "applies to none". On a chain id with no
  // corresponding CancelLeasesToDisabledAliases-<chainId>.json patch-data resource (stagenet 'S',
  // testnet '!'), `isDefinedAt` returning true would have gone on to call `patchData`, whose
  // `readPatchData()` throws when `Source.fromResource` can't find that file. This mocks
  // Blockchain directly (rather than mutating the global AddressScheme.current, which is unsafe
  // under sbt's parallel suite execution, per the file-level comment above) to check
  // `isDefinedAt` alone, at the exact height SynchronousCalls activates, for both stagenet and
  // testnet chain ids.
  for (chainId <- Seq('S', '!')) {
    it should s"not be defined at (and so never call readPatchData for) chain id '$chainId', which has no patch-data resource file" in {
      val activationHeight = 12345
      val blockchain       = mock[Blockchain]
      (() => blockchain.settings).expects().anyNumberOfTimes().returning(
        BlockchainSettings(chainId, com.decentralchain.settings.TestFunctionalitySettings.Enabled, null, null)
      )
      (() => blockchain.activatedFeatures).expects().anyNumberOfTimes().returning(
        Map(BlockchainFeatures.SynchronousCalls.id -> Height(activationHeight))
      )
      (() => blockchain.height).expects().anyNumberOfTimes().returning(activationHeight)

      CancelLeasesToDisabledAliases.isDefinedAt(blockchain) shouldBe false
    }
  }

  it should "also not be defined at DCC's own mainnet chain id -- this Waves-only historical patch is fully disabled for DCC" in {
    val activationHeight = 12345
    val blockchain       = mock[Blockchain]
    (() => blockchain.settings).expects().anyNumberOfTimes().returning(
      BlockchainSettings('?', com.decentralchain.settings.TestFunctionalitySettings.Enabled, null, null)
    )
    (() => blockchain.activatedFeatures).expects().anyNumberOfTimes().returning(
      Map(BlockchainFeatures.SynchronousCalls.id -> Height(activationHeight))
    )
    (() => blockchain.height).expects().anyNumberOfTimes().returning(activationHeight)

    CancelLeasesToDisabledAliases.isDefinedAt(blockchain) shouldBe false
  }
}
