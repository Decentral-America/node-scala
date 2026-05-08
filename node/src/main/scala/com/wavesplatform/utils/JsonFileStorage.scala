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
  private val AES               = "AES"
  private val Algorithm         = AES + "/GCM/NoPadding"
  private val HashingAlgorithm  = "PBKDF2WithHmacSHA512"
  private val HashingIterations = 999999
  private val KeySizeBits       = 256
  private val GcmIvLength       = 12
  private val GcmTagLength      = 128
  private val SaltLength        = 32

  private val secureRandom = new SecureRandom()

  def prepareKey(key: String): SecretKeySpec = prepareKey(key, generateSalt())

  private def generateSalt(): Array[Byte] = {
    val salt = new Array[Byte](SaltLength)
    secureRandom.nextBytes(salt)
    salt
  }

  private def prepareKey(key: String, salt: Array[Byte]): SecretKeySpec = {
    import java.security.NoSuchAlgorithmException
    import java.security.spec.InvalidKeySpecException

    import javax.crypto.SecretKeyFactory
    import javax.crypto.spec.PBEKeySpec

    def hashPassword(password: Array[Char], salt: Array[Byte], iterations: Int, keyLength: Int): Array[Byte] =
      try {
        val keyFactory = SecretKeyFactory.getInstance(HashingAlgorithm)
        val keySpec    = new PBEKeySpec(password, salt, iterations, keyLength)
        val key        = keyFactory.generateSecret(keySpec)
        key.getEncoded
      } catch {
        case e @ (_: NoSuchAlgorithmException | _: InvalidKeySpecException) =>
          throw new RuntimeException("Password hashing error", e)
      }

    new SecretKeySpec(hashPassword(key.toCharArray, salt, HashingIterations, KeySizeBits), AES)
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
