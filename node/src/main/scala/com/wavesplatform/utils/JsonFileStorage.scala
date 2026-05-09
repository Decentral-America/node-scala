package com.wavesplatform.utils

import java.io.{File, PrintWriter}
import java.security.SecureRandom

import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import play.api.libs.json.{Json, Reads, Writes}
import java.util.Base64

import scala.io.Source
import scala.util.control.NonFatal

object JsonFileStorage {
  private val AES          = "AES"
  private val Algorithm    = AES + "/GCM/NoPadding"
  private val KeySizeBits  = 256
  private val GcmIvLength  = 12
  private val GcmTagLength = 128
  private val SaltLength   = 32

  // Argon2id parameters — OWASP Password Storage Cheat Sheet 2026 minimum:
  // m=19456 KiB (19 MiB), t=2 iterations, p=1 lane.
  // Identical to the monorepo (packages/crypto/src/deriveKey.ts) WASM implementation.
  private val Argon2Memory      = 19456 // KiB
  private val Argon2Iterations  = 2
  private val Argon2Parallelism = 1

  private val secureRandom = new SecureRandom()

  def prepareKey(key: String): SecretKeySpec = prepareKey(key, generateSalt())

  private def generateSalt(): Array[Byte] = {
    val salt = new Array[Byte](SaltLength)
    secureRandom.nextBytes(salt)
    salt
  }

  /** Derives a 256-bit AES key from a password and salt using Argon2id.
    * Argon2id is the OWASP #1 recommended KDF (memory-hard, GPU-resistant).
    * Uses Bouncy Castle's Argon2BytesGenerator — no additional dependency
    * (bcprov-jdk18on is already required by the node for Ethereum/web3j).
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
    new SecretKeySpec(keyBytes, AES)
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

  /** Encrypts value with a fresh random salt and IV.
    * Output format (base64-encoded): [salt (32 bytes) || IV (12 bytes) || ciphertext+GCM-tag]
    */
  private def encrypt(password: String, value: String): String = {
    try {
      val salt = generateSalt()
      val key = prepareKey(password, salt)
      val iv = new Array[Byte](GcmIvLength)
      secureRandom.nextBytes(iv)
      val cipher: Cipher = Cipher.getInstance(Algorithm)
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GcmTagLength, iv))
      val ciphertext = cipher.doFinal(value.utf8Bytes)
      new String(Base64.getEncoder.encode(salt ++ iv ++ ciphertext))
    } catch {
      case NonFatal(e) =>
        throw new RuntimeException("File storage encrypt error", e)
    }
  }

  /** Decrypts value by extracting salt and IV from the blob prefix. */
  private def decrypt(password: String, encryptedValue: String): String = {
    try {
      val decoded = Base64.getDecoder.decode(encryptedValue)
      val salt = decoded.take(SaltLength)
      val iv = decoded.slice(SaltLength, SaltLength + GcmIvLength)
      val ciphertext = decoded.drop(SaltLength + GcmIvLength)
      val key = prepareKey(password, salt)
      val cipher: Cipher = Cipher.getInstance(Algorithm)
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GcmTagLength, iv))
      new String(cipher.doFinal(ciphertext))
    } catch {
      case NonFatal(e) =>
        throw new RuntimeException("File storage decrypt error", e)
    }
  }
}
