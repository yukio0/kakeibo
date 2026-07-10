package jp.yukio0.kakeibo.auth

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Service

@Service
class TotpService {

  private val secureRandom = SecureRandom()

  fun generateSecret(): String {
    val bytes = ByteArray(SECRET_BYTES)
    secureRandom.nextBytes(bytes)
    return Base32.encode(bytes)
  }

  fun generateOtpAuthUri(username: String, secret: String): String {
    val label = urlEncode("$ISSUER:$username")
    val issuer = urlEncode(ISSUER)
    return "otpauth://totp/$label?secret=$secret&issuer=$issuer&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SECONDS"
  }

  fun isValidCode(
    secret: String,
    code: String,
    instant: Instant = Instant.now(),
  ): Boolean {
    val normalizedCode = code.trim()
    if (!CODE_PATTERN.matches(normalizedCode)) {
      return false
    }

    val counter = instant.epochSecond / PERIOD_SECONDS
    return (-ALLOWED_DRIFT_STEPS..ALLOWED_DRIFT_STEPS).any { offset ->
      generateCode(secret, counter + offset) == normalizedCode
    }
  }

  fun generateCode(secret: String, instant: Instant = Instant.now()): String =
    generateCode(secret, instant.epochSecond / PERIOD_SECONDS)

  private fun generateCode(secret: String, counter: Long): String {
    val key = Base32.decode(secret)
    val message = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array()
    val hmac = Mac.getInstance("HmacSHA1")
    hmac.init(SecretKeySpec(key, "HmacSHA1"))
    val hash = hmac.doFinal(message)
    val offset = hash.last().toInt() and 0x0f
    val binary =
      ((hash[offset].toInt() and 0x7f) shl 24) or
        ((hash[offset + 1].toInt() and 0xff) shl 16) or
        ((hash[offset + 2].toInt() and 0xff) shl 8) or
        (hash[offset + 3].toInt() and 0xff)
    val otp = binary % OTP_MODULO
    return otp.toString().padStart(DIGITS, '0')
  }

  private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

  private object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
      val result = StringBuilder()
      var buffer = 0
      var bitsLeft = 0

      bytes.forEach { byte ->
        buffer = (buffer shl Byte.SIZE_BITS) or (byte.toInt() and 0xff)
        bitsLeft += Byte.SIZE_BITS

        while (bitsLeft >= BITS_PER_BASE32_CHAR) {
          val index = (buffer shr (bitsLeft - BITS_PER_BASE32_CHAR)) and 0x1f
          result.append(ALPHABET[index])
          bitsLeft -= BITS_PER_BASE32_CHAR
          buffer = buffer and ((1 shl bitsLeft) - 1)
        }
      }

      if (bitsLeft > 0) {
        val index = (buffer shl (BITS_PER_BASE32_CHAR - bitsLeft)) and 0x1f
        result.append(ALPHABET[index])
      }

      return result.toString()
    }

    fun decode(value: String): ByteArray {
      val bytes = mutableListOf<Byte>()
      var buffer = 0
      var bitsLeft = 0

      value
        .uppercase()
        .filter { it != '=' && !it.isWhitespace() }
        .forEach { character ->
          val index = ALPHABET.indexOf(character)
          require(index >= 0) { "Invalid Base32 character" }
          buffer = (buffer shl BITS_PER_BASE32_CHAR) or index
          bitsLeft += BITS_PER_BASE32_CHAR

          while (bitsLeft >= Byte.SIZE_BITS) {
            bytes.add(((buffer shr (bitsLeft - Byte.SIZE_BITS)) and 0xff).toByte())
            bitsLeft -= Byte.SIZE_BITS
            buffer = buffer and ((1 shl bitsLeft) - 1)
          }
        }

      return bytes.toByteArray()
    }
  }

  private companion object {
    private const val ISSUER = "Kakeibo"
    private const val SECRET_BYTES = 20
    private const val PERIOD_SECONDS = 30L
    private const val DIGITS = 6
    private const val ALLOWED_DRIFT_STEPS = 1
    private const val BITS_PER_BASE32_CHAR = 5
    private const val OTP_MODULO = 1_000_000
    private val CODE_PATTERN = Regex("""\d{$DIGITS}""")
  }
}
