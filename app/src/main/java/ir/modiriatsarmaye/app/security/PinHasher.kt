package ir.modiriatsarmaye.app.security

import ir.modiriatsarmaye.app.util.PersianUtils
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * مشتق‌سازی امن رمز عبور با PBKDF2-HMAC-SHA256، نمک تصادفی و مقایسه زمان‌ثابت (Constant-time)
 */
object Pbkdf2PinHasher {
    const val DEFAULT_ITERATIONS = 120_000
    const val SALT_SIZE_BYTES = 16
    const val HASH_SIZE_BITS = 256
    const val MIN_PIN_LENGTH = 4
    const val MAX_PIN_LENGTH = 8

    private val secureRandom = SecureRandom()

    fun normalizeDigits(rawPin: String): String {
        return PersianUtils.toEnglishDigits(rawPin).trim()
    }

    /**
     * اعتبارسنجی فرمت PIN جدید (۴ تا ۸ رقم عددی)
     */
    fun validateNewPinFormat(rawPin: String): String? {
        val normalized = normalizeDigits(rawPin)
        if (normalized.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
            return null
        }
        if (!normalized.all { it in '0'..'9' }) {
            return null
        }
        return normalized
    }

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    fun deriveHash(
        pin: String,
        salt: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS
    ): ByteArray {
        require(pin.isNotBlank()) { "PIN must not be empty or blank" }
        require(salt.size >= SALT_SIZE_BYTES) { "Salt size must be at least $SALT_SIZE_BYTES bytes" }
        require(iterations > 0) { "Iterations must be positive" }

        val chars = pin.toCharArray()
        return try {
            deriveViaSecretKeyFactory(chars, salt, iterations)
        } catch (_: NoSuchAlgorithmException) {
            // پشتیبانی کامل از PBKDF2-HMAC-SHA256 در نسخه‌های قدیمی‌تر اندروید (API 24-25)
            deriveRfc8018HmacSha256(chars, salt, iterations, HASH_SIZE_BITS / 8)
        } finally {
            chars.fill('\u0000')
        }
    }

    fun verify(
        candidatePin: String,
        salt: ByteArray,
        expectedHash: ByteArray,
        iterations: Int = DEFAULT_ITERATIONS
    ): Boolean {
        if (candidatePin.isBlank() || salt.size < SALT_SIZE_BYTES || expectedHash.isEmpty() || iterations <= 0) {
            return false
        }
        val actualHash = deriveHash(candidatePin, salt, iterations)
        return try {
            actualHash.size == expectedHash.size && MessageDigest.isEqual(actualHash, expectedHash)
        } finally {
            actualHash.fill(0)
        }
    }

    private fun deriveViaSecretKeyFactory(
        chars: CharArray,
        salt: ByteArray,
        iterations: Int
    ): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(chars, salt, iterations, HASH_SIZE_BITS)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun deriveRfc8018HmacSha256(
        chars: CharArray,
        salt: ByteArray,
        iterations: Int,
        dkLenBytes: Int
    ): ByteArray {
        val passwordBytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars)).let { buf ->
            ByteArray(buf.remaining()).also { buf.get(it) }
        }
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))

            val hLen = mac.macLength
            val blockCount = (dkLenBytes + hLen - 1) / hLen
            val output = ByteArray(dkLenBytes)
            val inputBlock = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, inputBlock, 0, salt.size)

            var outputOffset = 0
            for (blockIndex in 1..blockCount) {
                inputBlock[salt.size] = (blockIndex ushr 24).toByte()
                inputBlock[salt.size + 1] = (blockIndex ushr 16).toByte()
                inputBlock[salt.size + 2] = (blockIndex ushr 8).toByte()
                inputBlock[salt.size + 3] = blockIndex.toByte()

                var u = mac.doFinal(inputBlock)
                val t = u.copyOf()
                for (iter in 2..iterations) {
                    u = mac.doFinal(u)
                    for (j in t.indices) {
                        t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                    }
                }
                val toCopy = minOf(hLen, dkLenBytes - outputOffset)
                System.arraycopy(t, 0, output, outputOffset, toCopy)
                outputOffset += toCopy
                t.fill(0)
            }
            output
        } finally {
            passwordBytes.fill(0)
        }
    }
}
