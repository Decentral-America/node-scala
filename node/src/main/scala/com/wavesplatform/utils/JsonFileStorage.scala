package com.wavesplatform.utils

import java.io.{File, PrintWriter}
import java.security.SecureRandom

import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import play.api.libs.json.{Json, Reads, Writes}
import java.util.Base64

import scala.io.Source
import scala.util.control.NonFatal

object JsonFileStorage {
  // ChaCha20-Poly1305 (RFC 7539, Java 11+ built-in — no BC dependency for cipher).
  // 96-bit nonce: safe at wallet scale (2^48 birthday bound = 281 trillion encryptions).
  // Matches node-go (XChaCha20-Poly1305) and monorepo in cipher family (ChaCha/Poly1305 AEAD).
  // Note: XChaCha20 (192-bit nonce) is not available in Java's built-in or BC — 96-bit is the max.
  private val ChaCha20     = "ChaCha20"
  private val Algorithm    = "ChaCha20-Poly1305"
  private val KeySizeBits  = 256
  private val NonceLength  = 12
  private val SaltLength   = 32

  // Argon2id parameters — high-security tier.
  // Matches node-go (pkg/wallet/crypt.go) and monorepo (packages/crypto/src/lib.rs).
  // All wallet encryption across the DCC ecosystem uses identical KDF parameters.
  private val Argon2Memory      = 65536 // KiB (64 MiB)
  private val Argon2Iterations  = 4
  private val Argon2Parallelism = 4

  private val secureRandom = new SecureRandom()

  def prepareKey(key: String): SecretKeySpec = prepareKey(key, generateSalt())

  private def generateSalt(): Array[Byte] = {
    val salt = new Array[Byte](SaltLength)
    secureRandom.nextBytes(salt)
    salt
  }

  /** Derives a 256-bit ChaCha20 key from a password and salt using Argon2id.
    * Argon2id is the OWASP #1 recommended KDF (memory-hard, GPU-resistant).
    * Uses Bouncy Castle's Argon2BytesGenerator — BC is already required for Blake2b/Keccak.
    */
  private def prepareKey(key: String, salt: Array[Byte]): SecretKeySpec = {
    import org.bouncycastle.crypto.generators.Argon2BytesGenerator
    import org.bouncycastle.crypto.params.Argon2Parameters

    val params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withSalt(salt)
      .withMemoryAsKB(Argon2Memory)
      .withIterations(Argon2Iterations)
      .withParallelism(Argon2Parallelism)
      .build()

    val generator = new Argon2BytesGenerator()
    generator.init(params)

    val keyBytes = new Array[Byte](KeySizeBits / 8)
    generator.generateBytes(key.toCharArray, keyBytes)
    new SecretKeySpec(keyBytes, ChaCha20)
  }

  def save[T](value: T, path: String, password: Option[String])(implicit w: Writes[T]): Unit = {
    val folder = new File(path).getParentFile
    if (!folder.exists()) folder.mkdirs()

    val file = new PrintWriter(path)
    try {
      val json = Json.toJson(value).toString()
      val data = password.fold(json)(p => encrypt(p, json))
      file.write(data)
    } finally file.close()
  }

  def save[T](value: T, path: String)(implicit w: Writes[T]): Unit =
    save(value, path, None)

  def load[T](path: String, password: Option[String] = None)(implicit r: Reads[T]): T = {
    val file = Source.fromFile(path)
    try {
      val dataStr = file.mkString
      Json.parse(password.fold(dataStr)(p => decrypt(p, dataStr))).as[T]
    } finally file.close()
  }

  def load[T](path: String)(implicit r: Reads[T]): T =
    load(path, Option.empty[String])

  /** Encrypts value with a fresh random salt and nonce.
    * Output format (base64-encoded): [salt[32] || nonce[12] || ciphertext+Poly1305-tag]
    * ChaCha20-Poly1305 tag is 16 bytes, appended implicitly by the JCE cipher.
    */
  private def encrypt(password: String, value: String): String = {
    try {
      val salt = generateSalt()
      val key = prepareKey(password, salt)
      val nonce = new Array[Byte](NonceLength)
      secureRandom.nextBytes(nonce)
      val cipher: Cipher = Cipher.getInstance(Algorithm)
      cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce))
      val ciphertext = cipher.doFinal(value.utf8Bytes)
      new String(Base64.getEncoder.encode(salt ++ nonce ++ ciphertext))
    } catch {
      case NonFatal(e) =>
        throw new RuntimeException("File storage encrypt error", e)
    }
  }

  /** Decrypts value by extracting salt and nonce from the blob prefix.
    * Throws if the Poly1305 authentication tag is invalid (wrong password or corrupted data).
    */
  private def decrypt(password: String, encryptedValue: String): String = {
    try {
      val decoded = Base64.getDecoder.decode(encryptedValue)
      val salt      = decoded.take(SaltLength)
      val nonce     = decoded.slice(SaltLength, SaltLength + NonceLength)
      val ciphertext = decoded.drop(SaltLength + NonceLength)
      val key = prepareKey(password, salt)
      val cipher: Cipher = Cipher.getInstance(Algorithm)
      cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce))
      new String(cipher.doFinal(ciphertext))
    } catch {
      case NonFatal(e) =>
        throw new RuntimeException("File storage decrypt error", e)
    }
  }
}
