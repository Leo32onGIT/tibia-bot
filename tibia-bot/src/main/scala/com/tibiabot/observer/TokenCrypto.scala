package com.tibiabot.observer

import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/** AES-256-GCM for the Observer access tokens at rest.
 *
 *  The token grants access to a member's own account link, so it is never stored
 *  in plaintext and never logged. Output is base64 of `[12-byte IV || ciphertext
 *  || 16-byte tag]`; the IV is random per encryption so the same token encrypts
 *  differently each time. The key is derived from a configured secret (SHA-256 of
 *  it) so any non-empty secret yields a valid 256-bit key. */
final class TokenCrypto(keyBytes: Array[Byte]) {
  private val random = new SecureRandom()
  private val key = new SecretKeySpec(keyBytes, "AES")
  private val IvLength = 12
  private val TagBits = 128

  def encrypt(plaintext: String): String = {
    val iv = new Array[Byte](IvLength)
    random.nextBytes(iv)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TagBits, iv))
    val ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"))
    Base64.getEncoder.encodeToString(iv ++ ciphertext)
  }

  def decrypt(encoded: String): String = {
    val bytes = Base64.getDecoder.decode(encoded)
    val (iv, ciphertext) = bytes.splitAt(IvLength)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TagBits, iv))
    new String(cipher.doFinal(ciphertext), "UTF-8")
  }
}

object TokenCrypto {
  /** Derive the AES key as SHA-256 of the configured secret. */
  def fromSecret(secret: String): TokenCrypto =
    new TokenCrypto(MessageDigest.getInstance("SHA-256").digest(secret.getBytes("UTF-8")))
}
