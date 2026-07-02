package com.decentralchain.state.patch

import com.decentralchain.account.PublicKey
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.Base58
import com.decentralchain.db.WithDomain
import com.decentralchain.db.WithState.AddrWithBalance
import com.decentralchain.features.BlockchainFeatures
import com.decentralchain.history.Domain
import com.decentralchain.settings.DCCSettings
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.*
import com.decentralchain.transaction.TxHelpers

// DCC's own chain has no CancelLeasesToDisabledAliases-<chainId>.json patch data (unlike the legacy
// Waves 'W'/'T' networks, which had historical leases to since-disabled aliases to cancel): the resource
// file for DCC's chain id is intentionally empty. These tests exercise the patch mechanism's no-op path
// on the current chain instead of mutating the global AddressScheme singleton to impersonate 'W' — doing
// so is unsafe under sbt's parallel suite execution, since other concurrently-running suites read the
// same mutable AddressScheme.current.
class CancelLeasesToDisabledAliasesSpec extends FlatSpec with WithDomain {
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
}
