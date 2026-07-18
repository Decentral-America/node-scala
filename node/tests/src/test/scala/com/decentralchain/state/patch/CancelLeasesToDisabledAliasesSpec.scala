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
import org.scalatest.BeforeAndAfterAll

class CancelLeasesToDisabledAliasesSpec extends FlatSpec with WithDomain with BeforeAndAfterAll {
  val MainnetSettings: DCCSettings = {
    import SettingsFromDefaultConfig.blockchainSettings.functionalitySettings as fs
    SettingsFromDefaultConfig.copy(
      blockchainSettings = SettingsFromDefaultConfig.blockchainSettings.copy(
        addressSchemeCharacter = 'W',
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

  "CancelLeasesToDisabledAliases" should "be applied only once" in
    withDomain(MainnetSettings, AddrWithBalance.enoughBalances(TxHelpers.defaultSigner)) { d =>
      testLeaseBalance(d).out shouldBe 0L

      d.appendKeyBlock()
      testLeaseBalance(d).out shouldBe -2562590821L

      d.appendMicroBlock(TxHelpers.transfer())
      d.appendMicroBlock(TxHelpers.transfer())
      d.appendMicroBlock(TxHelpers.transfer())
      d.appendKeyBlock()
      testLeaseBalance(d).out shouldBe -2562590821L
    }

  it should "be applied on extension apply" in
    withDomain(MainnetSettings, AddrWithBalance.enoughBalances(TxHelpers.defaultSigner)) { d =>
      testLeaseBalance(d).out shouldBe 0L
      d.appendBlock()
      testLeaseBalance(d).out shouldBe -2562590821L
      d.appendBlock()
      testLeaseBalance(d).out shouldBe -2562590821L
      d.appendBlock()
      testLeaseBalance(d).out shouldBe -2562590821L
    }

  // The patch fixture data (patches/CancelLeasesToDisabledAliases-W.json) is keyed to real historical
  // Waves mainnet ('W') addresses, independent of the process-global AddressScheme.current default ('?').
  private def testLeaseBalance(d: Domain) = {
    d.blockchain.leaseBalance(PublicKey(ByteStr(Base58.decode("6NxhjzayDTd52MJL2r6XupGDb7E1xQW7QppSPqo63gsx"))).toAddress('W'.toByte))
  }
}
