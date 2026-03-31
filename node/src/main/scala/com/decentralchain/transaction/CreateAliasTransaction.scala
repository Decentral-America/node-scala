package com.decentralchain.transaction

import com.google.common.primitives.Bytes
import com.decentralchain.account.*
import com.decentralchain.common.state.ByteStr
import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.crypto
import com.decentralchain.lang.ValidationError
import com.decentralchain.transaction.serialization.impl.CreateAliasTxSerializer
import com.decentralchain.transaction.validation.TxValidator
import com.decentralchain.transaction.validation.impl.CreateAliasTxValidator
import monix.eval.Coeval
import play.api.libs.json.JsObject

import scala.util.Try

final case class CreateAliasTransaction(
    version: TxVersion,
    sender: PublicKey,
    aliasName: String,
    fee: TxPositiveAmount,
    timestamp: TxTimestamp,
    proofs: Proofs,
    chainId: Byte
) extends Transaction(TransactionType.CreateAlias)
    with SigProofsSwitch
    with Versioned.ToV3
    with TxWithFee.InDcc
    with PBSince.V3 {

  lazy val alias: Alias = Alias.createWithChainId(aliasName, chainId, Some(chainId)).explicitGet()

  override val bodyBytes: Coeval[Array[Byte]] = Coeval.evalOnce(CreateAliasTxSerializer.bodyBytes(this))
  override val bytes: Coeval[Array[Byte]]     = Coeval.evalOnce(CreateAliasTxSerializer.toBytes(this))
  override val json: Coeval[JsObject]         = Coeval.evalOnce(CreateAliasTxSerializer.toJson(this))

  override val id: Coeval[ByteStr] = Coeval.evalOnce {
    ByteStr(crypto.fastHash(version match {
      case TxVersion.V1 | TxVersion.V2 => Bytes.concat(Array(tpe.id.toByte), alias.bytes)
      case _                           => bodyBytes()
    }))
  }
}

object CreateAliasTransaction extends TransactionParser {
  type TransactionT = CreateAliasTransaction

  val typeId: TxType = 10: Byte

  implicit val validator: TxValidator[CreateAliasTransaction] = CreateAliasTxValidator

  implicit def sign(tx: CreateAliasTransaction, privateKey: PrivateKey): CreateAliasTransaction =
    tx.copy(proofs = Proofs(crypto.sign(privateKey, tx.bodyBytes())))

  override def parseBytes(bytes: Array[TxVersion]): Try[CreateAliasTransaction] =
    CreateAliasTxSerializer.parseBytes(bytes)

  def create(
      version: TxVersion,
      sender: PublicKey,
      aliasName: String,
      fee: Long,
      timestamp: TxTimestamp,
      proofs: Proofs,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, TransactionT] = {
    for {
      fee <- TxPositiveAmount(fee)(TxValidationError.InsufficientFee)
      tx  <- CreateAliasTransaction(version, sender, aliasName, fee, timestamp, proofs, chainId).validatedEither
    } yield tx
  }

  def signed(
      version: TxVersion,
      sender: PublicKey,
      alias: String,
      fee: Long,
      timestamp: TxTimestamp,
      signer: PrivateKey,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, TransactionT] =
    create(version, sender, alias, fee, timestamp, Nil, chainId).map(_.signWith(signer))

  def selfSigned(
      version: TxVersion,
      sender: KeyPair,
      aliasName: String,
      fee: Long,
      timestamp: TxTimestamp,
      chainId: Byte = AddressScheme.current.chainId
  ): Either[ValidationError, TransactionT] =
    signed(version, sender.publicKey, aliasName, fee, timestamp, sender.privateKey, chainId)
}
