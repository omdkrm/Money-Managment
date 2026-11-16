package ir.modiriatsarmaye.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableEntryException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystorePermanentInvalidationException(
    message: String,
    cause: Throwable? = null
) : SecurityException(message, cause)

class KeystoreUnavailableException(
    message: String,
    cause: Throwable? = null
) : SecurityException(message, cause)

enum class KeyHealthStatus {
    HEALTHY,
    NOT_CREATED,
    PERMANENTLY_UNAVAILABLE,
    TEMPORARILY_UNAVAILABLE
}

/**
 * انتزاع رمزنگاری متقارن AES-GCM برای امکان جایگزینی و آزمون ایزوله در محیط JVM
 */
interface AesGcmCipherProvider {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(payload: ByteArray): ByteArray
    fun deleteKeyIfPresent()
    fun checkKeyHealth(): KeyHealthStatus
}

/**
 * پیاده‌سازی واقعی با تکیه بر Android Keystore و AES-256-GCM بدون هیچ‌گونه کلید سخت‌کدشده یا fallback ضعیف
 */
class AndroidKeystoreAesGcmProvider(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : AesGcmCipherProvider {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv ?: throw KeystoreUnavailableException("Keystore returned empty IV")
            val ciphertext = cipher.doFinal(plaintext)

            ByteBuffer.allocate(2 + iv.size + ciphertext.size).apply {
                put(FORMAT_VERSION)
                put(iv.size.toByte())
                put(iv)
                put(ciphertext)
            }.array()
        } catch (e: KeystorePermanentInvalidationException) {
            throw e
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeystorePermanentInvalidationException("کلید امنیتی دستگاه نامعتبر شده است.", e)
        } catch (e: UnrecoverableKeyException) {
            throw KeystorePermanentInvalidationException("امکان بازیابی کلید امنیتی از Keystore وجود ندارد.", e)
        } catch (e: UnrecoverableEntryException) {
            throw KeystorePermanentInvalidationException("ورودی کلید امنیتی در Keystore قابل بازیابی نیست.", e)
        } catch (e: Exception) {
            throw KeystoreUnavailableException("دسترسی به Android Keystore برای رمزنگاری ممکن نشد.", e)
        }
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        if (payload.size < 3) {
            throw KeystoreUnavailableException("داده رمزنگاری‌شده نامعتبر است.")
        }
        val version = payload[0]
        if (version != FORMAT_VERSION) {
            throw KeystoreUnavailableException("نسخه داده رمزنگاری‌شده پشتیبانی نمی‌شود.")
        }
        val ivLen = payload[1].toInt() and 0xFF
        if (ivLen <= 0 || payload.size <= 2 + ivLen) {
            throw KeystoreUnavailableException("ساختار بردار اولیه (IV) نامعتبر است.")
        }

        val iv = payload.copyOfRange(2, 2 + ivLen)
        val ciphertext = payload.copyOfRange(2 + ivLen, payload.size)

        return try {
            val secretKey = loadExistingSecretKey()
                ?: throw KeystorePermanentInvalidationException("کلید امنیتی در Keystore یافت نشد.")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: KeystorePermanentInvalidationException) {
            throw e
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeystorePermanentInvalidationException("کلید امنیتی دستگاه به‌طور دائمی نامعتبر شده است.", e)
        } catch (e: UnrecoverableKeyException) {
            throw KeystorePermanentInvalidationException("کلید امنیتی قابل بازیابی نیست.", e)
        } catch (e: UnrecoverableEntryException) {
            throw KeystorePermanentInvalidationException("ورودی کلید امنیتی قابل بازیابی نیست.", e)
        } catch (e: AEADBadTagException) {
            throw KeystorePermanentInvalidationException("برچسب اصالت داده امنیتی معتبر نیست یا کلید تغییر کرده است.", e)
        } catch (e: ProviderException) {
            throw KeystoreUnavailableException("خطای ارائه‌دهنده امنیتی دستگاه در رمزگشایی.", e)
        } catch (e: Exception) {
            throw KeystoreUnavailableException("دسترسی به Android Keystore برای رمزگشایی ممکن نشد.", e)
        }
    }

    override fun deleteKeyIfPresent() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        } catch (_: Exception) {
            // پاک‌سازی بهترین تلاش در زمان بازیابی تاییدشده کاربر
        }
    }

    override fun checkKeyHealth(): KeyHealthStatus {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(keyAlias)) {
                return KeyHealthStatus.NOT_CREATED
            }
            val key = keyStore.getKey(keyAlias, null) as? SecretKey
                ?: return KeyHealthStatus.PERMANENTLY_UNAVAILABLE
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            KeyHealthStatus.HEALTHY
        } catch (t: Throwable) {
            classifyKeyHealthException(t)
        }
    }

    internal fun classifyKeyHealthException(error: Throwable): KeyHealthStatus {
        return when (error) {
            is KeyPermanentlyInvalidatedException,
            is UnrecoverableKeyException,
            is UnrecoverableEntryException -> KeyHealthStatus.PERMANENTLY_UNAVAILABLE
            is ProviderException -> KeyHealthStatus.TEMPORARILY_UNAVAILABLE
            else -> {
                val cause = error.cause
                if (cause != null && cause !== error) {
                    classifyKeyHealthException(cause)
                } else {
                    KeyHealthStatus.TEMPORARILY_UNAVAILABLE
                }
            }
        }
    }

    private fun loadExistingSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(keyAlias)) return null
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun getOrCreateSecretKey(): SecretKey {
        loadExistingSecretKey()?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "ir.modiriatsarmaye.app.pin_aes_gcm_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION: Byte = 1
    }
}
