package com.decentralchain.state.diffs

import com.decentralchain.account.{Address, KeyPair}
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.db.WithState
import com.decentralchain.lagonaki.mocks.TestBlock
import com.decentralchain.lang.directives.values.{Expression, V6}
import com.decentralchain.lang.script.v1.ExprScript
import com.decentralchain.lang.utils.compilerContext
import com.decentralchain.lang.v1.compiler.ExpressionCompiler
import com.decentralchain.lang.v1.parser.Parser
import com.decentralchain.state.diffs.TransactionDiffer.TransactionValidationError
import com.decentralchain.test.*
import com.decentralchain.test.DomainPresets.RideV6
import com.decentralchain.transaction.TxValidationError.ScriptExecutionError
import com.decentralchain.transaction.{ErrorWithLogPrinter, TxHelpers}
import org.scalatest.Inside

class TransactionValidationErrorPrintTest extends PropSpec with Inside with WithState {
  property("output transaction error should be easy to read") {
    val assetScript =
      s"""
         | let NETWORKBYTE = takeRight(toBytes(87), 1)
         |
         | match (tx) {
         |     # Only allow transfer transactions
         |     case t:TransferTransaction => {
         |         let txWithoutAttachment = dropRight(t.bodyBytes, 97) + takeRight(toBytes(0), 1)
         |
         |         let recipientPublicKeyAndSignature = t.attachment
         |         let recipientPublicKey = take(recipientPublicKeyAndSignature, 32)
         |         let recipientSignature = takeRight(recipientPublicKeyAndSignature, 64)
         |
         |         let recipientPublicKeyHash = take(keccak256(blake2b256(recipientPublicKey)), 20)
         |         let rpkWithVersionAndByte = takeRight(toBytes(1), 1) + NETWORKBYTE + recipientPublicKeyHash
         |         let checksum = take(keccak256(blake2b256(rpkWithVersionAndByte)), 4)
         |         let recipientAddressFromPublicKey = rpkWithVersionAndByte + checksum
         |         let recipientAddressFromTx = addressFromRecipient(t.recipient).bytes
         |         let recipientAddressStr = toBase58String(recipientAddressFromPublicKey)
         |         let big = base64'${"a" * 2048}'
         |
         |         if (big == big && recipientAddressFromPublicKey != recipientAddressFromTx) then throw(
         |             "Recipient address error:" + recipientAddressStr
         |             ) else {
         |           if (!sigVerify(txWithoutAttachment, recipientSignature, recipientPublicKey))
         |             then true
         |             else false
         |         }
         |     }
         |     case _ => throw("unexpected")
         | }
      """.stripMargin

    val untypedScript = Parser.parseExpr(assetScript).get.value

    val typedScript = ExprScript(V6, ExpressionCompiler(compilerContext(V6, Expression, isAssetScript = false), V6, untypedScript).explicitGet()._1)
      .explicitGet()

    val preTypedScript =
      ExprScript(
        V6,
        ExpressionCompiler(compilerContext(V6, Expression, isAssetScript = false), V6, Parser.parseExpr("true").get.value).explicitGet()._1
      )
        .explicitGet()

    val seed     = Address.fromString("3DUo3aYXPFLB99RZEGAMFEgLNompgDdZ3ix").explicitGet()
    val master   = Address.fromString("3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH").explicitGet()
    val genesis1 = TxHelpers.genesis(master, 1000000000, timestamp = 0)
    val genesis2 = TxHelpers.genesis(KeyPair(master.bytes).toAddress, 1000000000, timestamp = 0)
    val genesis3 = TxHelpers.genesis(KeyPair(seed.bytes).toAddress, 1000000000, timestamp = 0)

    val issueTransaction = TxHelpers.issue(
      issuer = KeyPair(seed.bytes),
      amount = 100,
      name = "name",
      reissuable = false,
      script = Some(preTypedScript),
      fee = 100000000,
      timestamp = 0
    )

    val preTransferTransaction = TxHelpers.transfer(
      from = KeyPair(seed.bytes),
      to = KeyPair(master.bytes).toAddress,
      amount = 1,
      asset = issueTransaction.asset,
      fee = 10400000,
      timestamp = 0
    )

    val preSetAssetScriptTransaction = TxHelpers.setAssetScript(
      acc = KeyPair(seed.bytes),
      asset = issueTransaction.asset,
      script = typedScript,
      fee = 100000000,
      timestamp = 0
    )

    val transferTransaction = TxHelpers.transfer(KeyPair(master.bytes), master, 1, issueTransaction.asset, fee = 10000000, timestamp = 0)

    assertDiffEi(
      Seq(TestBlock.create(Seq(genesis1, genesis2, genesis3, issueTransaction, preTransferTransaction, preSetAssetScriptTransaction))),
      TestBlock.create(Seq(transferTransaction)),
      RideV6.blockchainSettings.functionalitySettings,
      enableExecutionLog = true
    ) { error =>
      inside(error) { case Left(TransactionValidationError(see: ScriptExecutionError, _)) =>
        val expected =
          f"""
             |	$$match0 = TransferTransaction(
             |		recipient = Address(
             |			bytes = base58'3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH'
             |		)
             |		timestamp = 0
             |		bodyBytes = base58'ZEFLEsXocCjCzcC774iqMLxj5aDbVfQvZg5vNQQePJ8wguKk1ApXm45bn7LXPRoi6hqX5uney6fPove7RLKXpXj7qpiZgBaLYqMJKbwh94cRfTgKfkvSZjRbfQVmgkLBftKXR11BUaRbYzXrJL2Wav26CFCYF8gUZx3'
             |		assetId = base58'xN34bJHzk62RRXVK19r4twCdTJeC8eegTKjBaYLqTJ2'
             |		feeAssetId = Unit
             |		amount = 1
             |		version = 2
             |		id = base58'HDj3q744uQUU8U2i6Fnt7rEtxHKDg8xxCvfmruJutYuw'
             |		senderPublicKey = base58'5VhP2NA8GKYaDvnPVcSuRxSEX5jscMW2PP5ZwmzsBonA'
             |		attachment = base58''
             |		sender = Address(
             |			bytes = base58'3DVyt7N97dZ3HDarL64SGmaXVKsdobGnrBi'
             |		)
             |		fee = 10000000
             |	)
             |	$$isInstanceOf.@args = [
             |		TransferTransaction(
             |			recipient = Address(
             |				bytes = base58'3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH'
             |			)
             |			timestamp = 0
             |			bodyBytes = base58'ZEFLEsXocCjCzcC774iqMLxj5aDbVfQvZg5vNQQePJ8wguKk1ApXm45bn7LXPRoi6hqX5uney6fPove7RLKXpXj7qpiZgBaLYqMJKbwh94cRfTgKfkvSZjRbfQVmgkLBftKXR11BUaRbYzXrJL2Wav26CFCYF8gUZx3'
             |			assetId = base58'xN34bJHzk62RRXVK19r4twCdTJeC8eegTKjBaYLqTJ2'
             |			feeAssetId = Unit
             |			amount = 1
             |			version = 2
             |			id = base58'HDj3q744uQUU8U2i6Fnt7rEtxHKDg8xxCvfmruJutYuw'
             |			senderPublicKey = base58'5VhP2NA8GKYaDvnPVcSuRxSEX5jscMW2PP5ZwmzsBonA'
             |			attachment = base58''
             |			sender = Address(
             |				bytes = base58'3DVyt7N97dZ3HDarL64SGmaXVKsdobGnrBi'
             |			)
             |			fee = 10000000
             |		),
             |		"TransferTransaction"
             |	]
             |	$$isInstanceOf.@complexity = 1
             |	@complexityLimit = 2147483646
             |	big = base64'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
             |	==.@args = [
             |		base64'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
             |		base64'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
             |	]
             |	==.@complexity = 1
             |	@complexityLimit = 2147483645
             |	toBytes.@args = [
             |		1
             |	]
             |	toBytes.@complexity = 1
             |	@complexityLimit = 2147483644
             |	takeRight.@args = [
             |		base58'11111112',
             |		1
             |	]
             |	takeRight.@complexity = 6
             |	@complexityLimit = 2147483638
             |	toBytes.@args = [
             |		87
             |	]
             |	toBytes.@complexity = 1
             |	@complexityLimit = 2147483637
             |	takeRight.@args = [
             |		base58'11111112W',
             |		1
             |	]
             |	takeRight.@complexity = 6
             |	@complexityLimit = 2147483631
             |	NETWORKBYTE = base58'2W'
             |	+.@args = [
             |		base58'2',
             |		base58'2W'
             |	]
             |	+.@complexity = 2
             |	@complexityLimit = 2147483629
             |	t = TransferTransaction(
             |		recipient = Address(
             |			bytes = base58'3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH'
             |		)
             |		timestamp = 0
             |		bodyBytes = base58'ZEFLEsXocCjCzcC774iqMLxj5aDbVfQvZg5vNQQePJ8wguKk1ApXm45bn7LXPRoi6hqX5uney6fPove7RLKXpXj7qpiZgBaLYqMJKbwh94cRfTgKfkvSZjRbfQVmgkLBftKXR11BUaRbYzXrJL2Wav26CFCYF8gUZx3'
             |		assetId = base58'xN34bJHzk62RRXVK19r4twCdTJeC8eegTKjBaYLqTJ2'
             |		feeAssetId = Unit
             |		amount = 1
             |		version = 2
             |		id = base58'HDj3q744uQUU8U2i6Fnt7rEtxHKDg8xxCvfmruJutYuw'
             |		senderPublicKey = base58'5VhP2NA8GKYaDvnPVcSuRxSEX5jscMW2PP5ZwmzsBonA'
             |		attachment = base58''
             |		sender = Address(
             |			bytes = base58'3DVyt7N97dZ3HDarL64SGmaXVKsdobGnrBi'
             |		)
             |		fee = 10000000
             |	)
             |	recipientPublicKeyAndSignature = base58''
             |	take.@args = [
             |		base58'',
             |		32
             |	]
             |	take.@complexity = 6
             |	@complexityLimit = 2147483623
             |	recipientPublicKey = base58''
             |	blake2b256.@args = [
             |		base58''
             |	]
             |	blake2b256.@complexity = 136
             |	@complexityLimit = 2147483487
             |	keccak256.@args = [
             |		base58'xyw95Bsby3s4mt6f4FmFDnFVpQBAeJxBFNGzu2cX4dM'
             |	]
             |	keccak256.@complexity = 195
             |	@complexityLimit = 2147483292
             |	take.@args = [
             |		base58'DRtdYbxMg7YHw4acvDP6xQrvmsRAz3K7gSkH3xBJ5CTL',
             |		20
             |	]
             |	take.@complexity = 6
             |	@complexityLimit = 2147483286
             |	recipientPublicKeyHash = base58'3aDy5kHaDeXWfQwMrBCRvd6r7gzg'
             |	+.@args = [
             |		base58'6v',
             |		base58'3aDy5kHaDeXWfQwMrBCRvd6r7gzg'
             |	]
             |	+.@complexity = 2
             |	@complexityLimit = 2147483284
             |	rpkWithVersionAndByte = base58'N8tNz9vAHAwFpa4A8Rgk45q8tNjeC'
             |	blake2b256.@args = [
             |		base58'N8tNz9vAHAwFpa4A8Rgk45q8tNjeC'
             |	]
             |	blake2b256.@complexity = 136
             |	@complexityLimit = 2147483148
             |	keccak256.@args = [
             |		base58'CSJhGcnZPNCcHG5gCZuHKArEg8MUy9ridbKZsryV8FEw'
             |	]
             |	keccak256.@complexity = 195
             |	@complexityLimit = 2147482953
             |	take.@args = [
             |		base58'4sAbTTxFgWFkHC5EutjwtRYgM3Q8V6aBD9EjDKVJ7byk',
             |		4
             |	]
             |	take.@complexity = 6
             |	@complexityLimit = 2147482947
             |	checksum = base58'2U8tZq'
             |	+.@args = [
             |		base58'N8tNz9vAHAwFpa4A8Rgk45q8tNjeC',
             |		base58'2U8tZq'
             |	]
             |	+.@complexity = 2
             |	@complexityLimit = 2147482945
             |	recipientAddressFromPublicKey = base58'3PJmMnHHVTTkzvF67HYFjrm5Vj96mM3UtLs'
             |	addressFromRecipient.@args = [
             |		Address(
             |			bytes = base58'3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH'
             |		)
             |	]
             |	addressFromRecipient.@complexity = 5
             |	@complexityLimit = 2147482940
             |	recipientAddressFromTx = base58'3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH'
             |	!=.@args = [
             |		base58'3PJmMnHHVTTkzvF67HYFjrm5Vj96mM3UtLs',
             |		base58'3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH'
             |	]
             |	!=.@complexity = 1
             |	@complexityLimit = 2147482939
             |	@a = base58'3PJmMnHHVTTkzvF67HYFjrm5Vj96mM3UtLs'
             |	@b = base58'3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH'
             |	==.@args = [
             |		base58'3PJmMnHHVTTkzvF67HYFjrm5Vj96mM3UtLs',
             |		base58'3DXNQqJKDxGGaoR3fkF4REwKjxwjHj2b3dH'
             |	]
             |	==.@complexity = 1
             |	@complexityLimit = 2147482938
             |	!.@args = [
             |		false
             |	]
             |	!.@complexity = 1
             |	@complexityLimit = 2147482937
             |	@p = false
             |	toBase58String.@args = [
             |		base58'3PJmMnHHVTTkzvF67HYFjrm5Vj96mM3UtLs'
             |	]
             |	toBase58String.@complexity = 3
             |	@complexityLimit = 2147482936
             |	recipientAddressStr = "3PJmMnHHVTTkzvF67HYFjrm5Vj96mM3UtLs"
             |	+.@args = [
             |		"Recipient address error:",
             |		"3PJmMnHHVTTkzvF67HYFjrm5Vj96mM3UtLs"
             |	]
             |	+.@complexity = 1
             |	@complexityLimit = 2147482935
             |	throw.@args = [
             |		"Recipient address error:3PJmMnHHVTTkzvF67HYFjrm5Vj96mM3UtLs"
             |	]
             |	throw.@complexity = 1
             |	@complexityLimit = 2147482934
             |""".stripMargin
        ErrorWithLogPrinter.logToString(see.log, Int.MaxValue) shouldBe expected
      }
    }
  }
}
