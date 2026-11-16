package ir.modiriatsarmaye.app.security

import android.content.Context
import android.util.Base64
import ir.modiriatsarmaye.app.data.local.SettingsDao
import org.json.JSONObject

sealed class PinVerificationResult {
    data object Success : PinVerificationResult()
    data object InvalidPin : PinVerificationResult()
    data object NoSecurePinConfigured : PinVerificationResult()
    data class KeystoreError(val isPermanent: Boolean, val message: String) : PinVerificationResult()
}

sealed class PinMutationResult {
    data object Success : PinMutationResult()
    data class ValidationError(val message: String) : PinMutationResult()
    data class KeystoreError(val isPermanent: Boolean, val message: String) : PinMutationResult()
}

sealed class PinMigrationResult {
    data object NotNeeded : PinMigrationResult()
    data object MigratedAndVerified : PinMigrationResult()
    data class FailedPreservedLegacy(
        val message: String,
        val isPermanentKeystoreFailure: Boolean
    ) : PinMigrationResult()
}

/**
 * انتزاع ذخیره‌سازی داده رمزنگاری‌شده برای جداسازی SharedPreferences در آزمون‌ها
 */
interface PinSecurityStorage {
    fun readEncryptedRecord(): String?
    fun writeEncryptedRecord(encodedBlob: String): Boolean
    fun clearEncryptedRecord(): Boolean
    fun readStagedEncryptedRecord(): String?
    fun writeStagedEncryptedRecord(encodedBlob: String): Boolean
    fun clearStagedEncryptedRecord(): Boolean
    fun promoteStagedRecordToActive(): Boolean
}

class SharedPrefsPinSecurityStorage(
    context: Context,
    prefsName: String = PREFS_FILE_NAME
) : PinSecurityStorage {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override fun readEncryptedRecord(): String? {
        return prefs.getString(KEY_ENCRYPTED_PIN_RECORD, null)?.takeIf { it.isNotBlank() }
    }

    override fun writeEncryptedRecord(encodedBlob: String): Boolean {
        return prefs.edit().putString(KEY_ENCRYPTED_PIN_RECORD, encodedBlob).commit()
    }

    override fun clearEncryptedRecord(): Boolean {
        return prefs.edit().remove(KEY_ENCRYPTED_PIN_RECORD).commit()
    }

    override fun readStagedEncryptedRecord(): String? {
        return prefs.getString(KEY_STAGED_ENCRYPTED_PIN_RECORD, null)?.takeIf { it.isNotBlank() }
    }

    override fun writeStagedEncryptedRecord(encodedBlob: String): Boolean {
        return prefs.edit().putString(KEY_STAGED_ENCRYPTED_PIN_RECORD, encodedBlob).commit()
    }

    override fun clearStagedEncryptedRecord(): Boolean {
        return prefs.edit().remove(KEY_STAGED_ENCRYPTED_PIN_RECORD).commit()
    }

    override fun promoteStagedRecordToActive(): Boolean {
        val staged = readStagedEncryptedRecord() ?: return false
        return prefs.edit()
            .putString(KEY_ENCRYPTED_PIN_RECORD, staged)
            .remove(KEY_STAGED_ENCRYPTED_PIN_RECORD)
            .commit()
    }

    companion object {
        const val PREFS_FILE_NAME = "pin_security_prefs"
        const val KEY_ENCRYPTED_PIN_RECORD = "encrypted_pin_record_v1"
        const val KEY_STAGED_ENCRYPTED_PIN_RECORD = "staged_encrypted_pin_record_v1"
    }
}

/**
 * مدیریت کامل رمزنگاری، بررسی و مهاجرت ایمن رمز ورود (PIN) بدون ذخیره متن خام
 */
class PinSecurityManager(
    private val storage: PinSecurityStorage,
    private val cipherProvider: AesGcmCipherProvider
) {
    fun hasSecurePin(): Boolean = storage.readEncryptedRecord() != null

    fun checkKeystoreHealth(): KeyHealthStatus = cipherProvider.checkKeyHealth()

    fun isKeystorePermanentlyUnavailable(): Boolean {
        if (!hasSecurePin()) return false
        val status = cipherProvider.checkKeyHealth()
        return status == KeyHealthStatus.PERMANENTLY_UNAVAILABLE || status == KeyHealthStatus.NOT_CREATED
    }

    /**
     * تنظیم یا بروزرسانی رمز PIN با استاندارد PBKDF2-HMAC-SHA256 و رمزنگاری Keystore AES-GCM
     * به همراه ذخیره‌سازی میانی (Staged)، بازخوانی تاییدشده و ارتقای اتمی پس از صحت‌سنجی
     */
    fun setPin(rawPin: String): PinMutationResult {
        val normalized = Pbkdf2PinHasher.validateNewPinFormat(rawPin)
            ?: return PinMutationResult.ValidationError(
                "رمز ورود باید بین ۴ تا ۸ رقم عددی باشد."
            )

        return storeHashedPinAndVerifyReadback(normalized)
    }

    /**
     * بررسی صحت رمز PIN با خواندن و رمزگشایی از مخزن امن فعال
     */
    fun verifyPin(rawCandidatePin: String): PinVerificationResult {
        if (Pbkdf2PinHasher.normalizeDigits(rawCandidatePin).isBlank()) {
            return PinVerificationResult.InvalidPin
        }
        val encodedBlob = storage.readEncryptedRecord()
            ?: return PinVerificationResult.NoSecurePinConfigured
        return verifyEncryptedBlob(rawCandidatePin, encodedBlob)
    }

    private fun verifyEncryptedBlob(
        rawCandidatePin: String,
        encodedBlob: String
    ): PinVerificationResult {
        val encryptedBytes = try {
            Base64.decode(encodedBlob, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return PinVerificationResult.KeystoreError(
                isPermanent = true,
                message = "داده رمزنگاری‌شده رمز ورود آسیب دیده است."
            )
        }

        val plaintextBytes = try {
            cipherProvider.decrypt(encryptedBytes)
        } catch (e: KeystorePermanentInvalidationException) {
            return PinVerificationResult.KeystoreError(
                isPermanent = true,
                message = e.message ?: "کلید امنیتی دستگاه در دسترس نیست. لطفاً از بازیابی بیومتریک استفاده کنید."
            )
        } catch (e: KeystoreUnavailableException) {
            return PinVerificationResult.KeystoreError(
                isPermanent = false,
                message = e.message ?: "سرویس امنیتی دستگاه موقتاً در دسترس نیست."
            )
        }

        return try {
            val payloadJson = JSONObject(String(plaintextBytes, Charsets.UTF_8))
            val version = payloadJson.optInt("v", 0)
            val iterations = payloadJson.optInt("iter", 0)
            val saltB64 = payloadJson.optString("salt", "")
            val hashB64 = payloadJson.optString("hash", "")
            if (version != RECORD_VERSION || iterations <= 0 || saltB64.isBlank() || hashB64.isBlank()) {
                return PinVerificationResult.KeystoreError(
                    isPermanent = true,
                    message = "ساختار رکورد امنیتی رمز ورود معتبر نیست."
                )
            }

            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val expectedHash = Base64.decode(hashB64, Base64.NO_WRAP)
            val normalizedCandidate = Pbkdf2PinHasher.normalizeDigits(rawCandidatePin)
            val matched = Pbkdf2PinHasher.verify(
                candidatePin = normalizedCandidate,
                salt = salt,
                expectedHash = expectedHash,
                iterations = iterations
            )
            if (matched) PinVerificationResult.Success else PinVerificationResult.InvalidPin
        } catch (_: Exception) {
            PinVerificationResult.KeystoreError(
                isPermanent = true,
                message = "خطا در پردازش رکورد امنیتی رمز ورود."
            )
        } finally {
            plaintextBytes.fill(0)
        }
    }

    /**
     * مهاجرت ایمن، تکرارپذیر (Idempotent) و مقاوم در برابر خطای رمز PIN متنی قدیمی به مخزن رمزنگاری‌شده.
     * - تنها پس از نوشتن میانی، بازخوانی، ارتقای اتمی و تایید تطابق با رمز قدیمی، ستون pinCode در Room پاک می‌شود.
     * - در صورت خطای Keystore یا عدم تطابق، رمز قدیمی دست‌نخورده حفظ و وضعیت قفل باقی می‌ماند.
     * - رمز امن از پیش مهاجرت‌شده هرگز بازنویسی یا خراب نمی‌شود.
     */
    suspend fun migrateLegacyPinIfNeeded(settingsDao: SettingsDao): PinMigrationResult {
        // پاک‌سازی رکوردهای میانی ناتمام احتمالی از اجرای قطع‌شده قبلی بدون دستکاری رکورد فعال
        storage.clearStagedEncryptedRecord()

        val currentSettings = settingsDao.getSettingsDirect() ?: return PinMigrationResult.NotNeeded
        val legacyRaw = currentSettings.pinCode
        if (legacyRaw.isBlank()) {
            return PinMigrationResult.NotNeeded
        }

        val normalizedLegacy = Pbkdf2PinHasher.normalizeDigits(legacyRaw)
        val existingSecureRecord = storage.readEncryptedRecord()

        if (existingSecureRecord != null) {
            // بررسی سناریوی بازیابی نیمه‌کاره (مخزن امن نوشته شده اما pinCode هنوز پاک نشده بود)
            return when (val verifyRes = verifyPin(normalizedLegacy)) {
                is PinVerificationResult.Success -> {
                    clearLegacyPinPreservingSettings(settingsDao)
                    PinMigrationResult.MigratedAndVerified
                }
                is PinVerificationResult.KeystoreError -> {
                    PinMigrationResult.FailedPreservedLegacy(
                        message = verifyRes.message,
                        isPermanentKeystoreFailure = verifyRes.isPermanent
                    )
                }
                is PinVerificationResult.InvalidPin,
                is PinVerificationResult.NoSecurePinConfigured -> {
                    // از بازنویسی خودکار مخزن امن موجود جلوگیری می‌شود تا رمز مهاجرت‌شده قبلی مخدوش نشود
                    PinMigrationResult.FailedPreservedLegacy(
                        message = "تضاد در وضعیت مهاجرت رمز ورود شناسایی شد؛ برای جلوگیری از تخریب اطلاعات امنیتی، داده‌های قبلی حفظ شدند.",
                        isPermanentKeystoreFailure = false
                    )
                }
            }
        }

        // مهاجرت اولیه: تولید نمک تصادفی + PBKDF2 + AES-GCM + بازخوانی میانی و ارتقای تاییدشده
        val mutationRes = storeHashedPinAndVerifyReadback(normalizedLegacy)
        return when (mutationRes) {
            is PinMutationResult.Success -> {
                clearLegacyPinPreservingSettings(settingsDao)
                PinMigrationResult.MigratedAndVerified
            }
            is PinMutationResult.ValidationError -> {
                PinMigrationResult.FailedPreservedLegacy(
                    message = mutationRes.message,
                    isPermanentKeystoreFailure = false
                )
            }
            is PinMutationResult.KeystoreError -> {
                PinMigrationResult.FailedPreservedLegacy(
                    message = mutationRes.message,
                    isPermanentKeystoreFailure = mutationRes.isPermanent
                )
            }
        }
    }

    /**
     * پاک‌سازی کامل رکورد فعال، رکورد میانی و کلید امنیتی هنگام غیرفعال‌سازی یا بازیابی تاییدشده توسط کاربر
     */
    fun clearPinRecordAndKey() {
        storage.clearStagedEncryptedRecord()
        storage.clearEncryptedRecord()
        cipherProvider.deleteKeyIfPresent()
    }

    private suspend fun clearLegacyPinPreservingSettings(settingsDao: SettingsDao) {
        val latest = settingsDao.getSettingsDirect() ?: return
        settingsDao.saveSettings(latest.copy(pinCode = ""))
    }

    private fun storeHashedPinAndVerifyReadback(normalizedPin: String): PinMutationResult {
        if (normalizedPin.isBlank()) {
            return PinMutationResult.ValidationError("رمز ورود نمی‌تواند خالی باشد.")
        }

        // پیش از شروع تغییر جدید، هر رکورد میانی ناتمام قبلی پاک می‌شود و رکورد فعال دست‌نخورده می‌ماند
        storage.clearStagedEncryptedRecord()

        val previousActiveBlob = storage.readEncryptedRecord()
        val salt = Pbkdf2PinHasher.generateSalt()
        val hash = Pbkdf2PinHasher.deriveHash(normalizedPin, salt, Pbkdf2PinHasher.DEFAULT_ITERATIONS)
        val payloadBytes = try {
            val json = JSONObject().apply {
                put("v", RECORD_VERSION)
                put("iter", Pbkdf2PinHasher.DEFAULT_ITERATIONS)
                put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                put("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            }
            json.toString().toByteArray(Charsets.UTF_8)
        } finally {
            salt.fill(0)
            hash.fill(0)
        }

        val encryptedBytes = try {
            cipherProvider.encrypt(payloadBytes)
        } catch (e: KeystorePermanentInvalidationException) {
            return PinMutationResult.KeystoreError(
                isPermanent = true,
                message = e.message ?: "کلید امنیتی دستگاه در دسترس نیست."
            )
        } catch (e: KeystoreUnavailableException) {
            return PinMutationResult.KeystoreError(
                isPermanent = false,
                message = e.message ?: "خطا در دسترسی به Keystore دستگاه."
            )
        } finally {
            payloadBytes.fill(0)
        }

        val encodedBlob = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

        // مرحله ۱: نوشتن در مخزن میانی (Staged) بدون تغییر دادن رکورد فعال قبلی
        val stagedWritten = storage.writeStagedEncryptedRecord(encodedBlob)
        if (!stagedWritten) {
            storage.clearStagedEncryptedRecord()
            return PinMutationResult.KeystoreError(
                isPermanent = false,
                message = "ذخیره‌سازی موقت رمز امن در حافظه دستگاه ناموفق بود."
            )
        }

        // مرحله ۲: بازخوانی و اعتبارسنجی رکورد میانی پیش از ارتقا به وضعیت فعال
        val stagedReadbackBlob = storage.readStagedEncryptedRecord()
        if (stagedReadbackBlob.isNullOrBlank()) {
            storage.clearStagedEncryptedRecord()
            return PinMutationResult.KeystoreError(
                isPermanent = false,
                message = "بازخوانی رکورد امنیتی موقت ناموفق بود."
            )
        }

        when (val readback = verifyEncryptedBlob(normalizedPin, stagedReadbackBlob)) {
            is PinVerificationResult.Success -> Unit
            is PinVerificationResult.KeystoreError -> {
                storage.clearStagedEncryptedRecord()
                return PinMutationResult.KeystoreError(
                    isPermanent = readback.isPermanent,
                    message = readback.message
                )
            }
            is PinVerificationResult.InvalidPin,
            is PinVerificationResult.NoSecurePinConfigured -> {
                storage.clearStagedEncryptedRecord()
                return PinMutationResult.KeystoreError(
                    isPermanent = false,
                    message = "بازخوانی امنیتی رمز ذخیره شده تایید نشد."
                )
            }
        }

        // مرحله ۳: ارتقای اتمی رکورد میانی تاییدشده به رکورد فعال
        val promoted = storage.promoteStagedRecordToActive()
        if (!promoted) {
            // در صورت شکست ارتقا، رکورد قبلی در صورت تغییر یافتن بازگردانی و مخزن میانی پاک‌سازی می‌شود
            if (previousActiveBlob != null && storage.readEncryptedRecord() != previousActiveBlob) {
                storage.writeEncryptedRecord(previousActiveBlob)
            } else if (previousActiveBlob == null && storage.readEncryptedRecord() != null) {
                storage.clearEncryptedRecord()
            }
            storage.clearStagedEncryptedRecord()
            return PinMutationResult.KeystoreError(
                isPermanent = false,
                message = "ارتقای نهایی رکورد امنیتی رمز ورود ناموفق بود؛ رمز قبلی حفظ شد."
            )
        }

        // پاک‌سازی قطعی مخزن میانی پس از ارتقای موفق (اگر پیاده‌سازی سفارشی آن را حذف نکرده باشد)
        storage.clearStagedEncryptedRecord()
        return PinMutationResult.Success
    }

    companion object {
        private const val RECORD_VERSION = 1

        fun createDefault(context: Context): PinSecurityManager {
            return PinSecurityManager(
                storage = SharedPrefsPinSecurityStorage(context.applicationContext),
                cipherProvider = AndroidKeystoreAesGcmProvider()
            )
        }
    }
}
