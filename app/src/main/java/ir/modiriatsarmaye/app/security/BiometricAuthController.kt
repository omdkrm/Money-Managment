package ir.modiriatsarmaye.app.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class DeviceAuthCapability {
    AVAILABLE,
    NOT_ENROLLED_OR_UNAVAILABLE
}

sealed class BiometricAuthOutcome {
    data object Succeeded : BiometricAuthOutcome()
    data object Canceled : BiometricAuthOutcome()
    data class Error(val errorCode: Int, val message: String) : BiometricAuthOutcome()
    data class NotAvailable(val message: String) : BiometricAuthOutcome()
}

/**
 * انتزاع احراز هویت بیومتریک / اعتبارنامه دستگاه جهت تست‌پذیری و رعایت عدم وابستگی مستقیم به سخت‌افزار در آزمون واحد
 */
interface BiometricAuthenticator {
    fun checkDeviceAuthCapability(): DeviceAuthCapability
    fun authenticate(
        title: String,
        subtitle: String,
        description: String,
        onResult: (BiometricAuthOutcome) -> Unit
    )
}

/**
 * پیاده‌سازی استاندارد AndroidX BiometricPrompt با پشتیبانی از بیومتریک و رمز قفل دستگاه
 */
class AndroidBiometricAuthenticator(
    private val activity: FragmentActivity
) : BiometricAuthenticator {

    internal fun resolveAllowedAuthenticators(sdkInt: Int = Build.VERSION.SDK_INT): Int {
        // در Android 11 (API 30) به بالا ترکیب BIOMETRIC_STRONG یا DEVICE_CREDENTIAL مجاز است.
        // در نسخه‌های پایین‌تر مطابق محدودیت AndroidX Biometric 1.1.0 از BIOMETRIC_WEAK یا DEVICE_CREDENTIAL استفاده می‌شود.
        return if (sdkInt >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }
    }

    internal fun buildPromptInfo(
        title: String,
        subtitle: String,
        description: String,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): BiometricPrompt.PromptInfo {
        val authenticators = resolveAllowedAuthenticators(sdkInt)
        // هنگام فعال بودن DEVICE_CREDENTIAL نباید setNegativeButtonText فراخوانی شود.
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setDescription(description)
            .setAllowedAuthenticators(authenticators)
            .build()
    }

    override fun checkDeviceAuthCapability(): DeviceAuthCapability {
        val biometricManager = BiometricManager.from(activity)
        val result = biometricManager.canAuthenticate(resolveAllowedAuthenticators())
        return if (result == BiometricManager.BIOMETRIC_SUCCESS) {
            DeviceAuthCapability.AVAILABLE
        } else {
            DeviceAuthCapability.NOT_ENROLLED_OR_UNAVAILABLE
        }
    }

    override fun authenticate(
        title: String,
        subtitle: String,
        description: String,
        onResult: (BiometricAuthOutcome) -> Unit
    ) {
        if (checkDeviceAuthCapability() != DeviceAuthCapability.AVAILABLE) {
            onResult(
                BiometricAuthOutcome.NotAvailable(
                    "احراز هویت بیومتریک یا رمز قفل دستگاه در این گوشی تنظیم نشده است."
                )
            )
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onResult(BiometricAuthOutcome.Succeeded)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    val isCanceled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    if (isCanceled) {
                        onResult(BiometricAuthOutcome.Canceled)
                    } else {
                        onResult(
                            BiometricAuthOutcome.Error(
                                errorCode = errorCode,
                                message = errString.toString().ifBlank { "خطا در احراز هویت دستگاه" }
                            )
                        )
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // کاربر می‌تواند مجدداً تلاش کند؛ دیالوگ BiometricPrompt باز می‌ماند.
                }
            }
        )

        val promptInfo = buildPromptInfo(
            title = title,
            subtitle = subtitle,
            description = description
        )
        prompt.authenticate(promptInfo)
    }
}
