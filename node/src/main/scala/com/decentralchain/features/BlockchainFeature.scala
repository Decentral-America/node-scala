package com.decentralchain.features

case class BlockchainFeature(id: Short, description: String)

object BlockchainFeatures {

  val SmallerMinimalGeneratingBalance = BlockchainFeature(1, "Minimum Generating Balance of 1000 DCC")
  val NG                              = BlockchainFeature(2, "NG Protocol")
  val MassTransfer                    = BlockchainFeature(3, "Mass Transfer Transaction")
  val SmartAccounts                   = BlockchainFeature(4, "Smart Accounts")
  val DataTransaction                 = BlockchainFeature(5, "Data Transaction")
  val BurnAnyTokens                   = BlockchainFeature(6, "Burn Any Tokens")
  val FeeSponsorship                  = BlockchainFeature(7, "Fee Sponsorship")
  val FairPoS                         = BlockchainFeature(8, "Fair PoS")
  val SmartAssets                     = BlockchainFeature(9, "Smart Assets")
  val SmartAccountTrading             = BlockchainFeature(10, "Smart Account Trading")
  val Ride4DApps                      = BlockchainFeature(11, "RIDE 4 DAPPS")
  val OrderV3                         = BlockchainFeature(12, "Order Version 3")
  val ReduceNFTFee                    = BlockchainFeature(13, "Reduce NFT fee")
  val BlockReward                     = BlockchainFeature(14, "Block Reward and Community Driven Monetary Policy")
  val BlockV5                         = BlockchainFeature(15, "Ride V4, VRF, Protobuf, Failed transactions")
  val SynchronousCalls                = BlockchainFeature(16, "Ride V5, dApp-to-dApp invocations")
  val RideV6                          = BlockchainFeature(17, "Ride V6, MetaMask support")
  val ConsensusImprovements           = BlockchainFeature(18, "Consensus and MetaMask updates")
  val BlockRewardDistribution         = BlockchainFeature(19, "Block Reward Distribution")
  val CappedReward                    = BlockchainFeature(20, "Capped XTN buy-back & DAO amounts")
  val CeaseXtnBuyback                 = BlockchainFeature(21, "Cease XTN buy-back")
  val LightNode                       = BlockchainFeature(22, "Light Node")
  val BoostBlockReward                = BlockchainFeature(23, "Boost Block Reward")
  val EcrecoverFix                    = BlockchainFeature(24, "ecrecover fix")
  val DeterministicFinality           = BlockchainFeature(25, "Deterministic Finality & RIDE V9")

  // Not exposed
  val ContinuationTransaction = BlockchainFeature(26, "Continuation Transaction")
  val LeaseExpiration         = BlockchainFeature(27, "Lease Expiration")

  // Modern Groth16 verifier: activates fastcrypto-zkp (arkworks/blst backed) for
  // groth16Verify_v2 RIDE opcode. Uses arkworks compressed wire format (snarkjs/circom
  // compatible). Requires coordinated all-node upgrade before activation height.
  // JNI symbol: Java_com_decentralchain_groth16_bls12_Groth16V2_verify
  val ModernGroth16Verifier = BlockchainFeature(28, "Modern Groth16 verifier (fastcrypto, arkworks format)")

  // SC-695: gates (a) rejection of InvokeScriptTransaction version V1/V2 against a dApp whose
  // deployed script is STDLIB V5+ (dApp-to-dApp sync calls require the V3 wire format) and
  // (b) a static per-step extra fee required when an InvokeScriptTransaction V3 invokes a
  // pre-V5 (V3/V4) dApp. Dormant until activated -- see
  // node/src/main/scala/com/decentralchain/state/diffs/invoke/InvokeVersionGating.scala and
  // docs/features/feature-30-sc695-spec.md.
  val InvokeVersionGating = BlockchainFeature(30, "InvokeScriptTransaction version gating and per-step invocation fee")

  // When next fork-parameter is created, you must replace all uses of the DummyFeature with the new one.
  val Dummy = BlockchainFeature(-1, "Non Votable!")

  private val dict = Seq(
    SmallerMinimalGeneratingBalance,
    NG,
    MassTransfer,
    SmartAccounts,
    DataTransaction,
    BurnAnyTokens,
    FeeSponsorship,
    FairPoS,
    SmartAccountTrading,
    SmartAssets,
    Ride4DApps,
    OrderV3,
    ReduceNFTFee,
    BlockReward,
    BlockV5,
    SynchronousCalls,
    RideV6,
    ConsensusImprovements,
    BlockRewardDistribution,
    CappedReward,
    CeaseXtnBuyback,
    LightNode,
    BoostBlockReward,
    EcrecoverFix,
    DeterministicFinality,
    ModernGroth16Verifier,
    InvokeVersionGating
  ).map(f => f.id -> f).toMap

  val implemented: Set[Short] = dict.keySet

  def feature(id: Short): Option[BlockchainFeature] = dict.get(id)
}
