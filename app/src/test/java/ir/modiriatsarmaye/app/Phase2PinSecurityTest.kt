package ir.modiriatsarmaye.app

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.fragment.app.FragmentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.modiriatsarmaye.app.data.local.AppDatabase
import ir.modiriatsarmaye.app.data.local.InitialData
import ir.modiriatsarmaye.app.data.model.*
import ir.modiriatsarmaye.app.data.repository.ValidationResult
import ir.modiriatsarmaye.app.data.repository.WealthRepository
import ir.modiriatsarmaye.app.security.*
import ir.modiriatsarmaye.app.MainActivity
import ir.modiriatsarmaye.app.WealthAppContent
import ir.modiriatsarmaye.app.data.local.SettingsDao
import ir.modiriatsarmaye.app.ui.screens.MoreScreen
import ir.modiriatsarmaye.app.ui.screens.PinLockScreen
import ir.modiriatsarmaye.app.ui.theme.ModiriatSarmayeTheme
import ir.modiriatsarmaye.app.ui.viewmodel.WealthUiState
import ir.modiriatsarmaye.app.ui.viewmodel.WealthViewModel
import ir.modiriatsarmaye.app.util.CalculationEngine
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableEntryException
import java.security.UnrecoverableKeyException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Phase2PinSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private val openedDatabases = mutableListOf<AppDatabase>()

    /**
     * پیاده‌سازی واقعی رمزنگاری AES-256-GCM در حافظه برای آزمون ایزوله JVM
     * (جایگزین AndroidKeyStore در Robolectric با قابلیت شبیه‌سازی خطای دائمی/موقت و خطای بازخوانی)
     */
    private class TestAesGcmCipherProvider(
        var failEncryptWithTransient: Boolean = false,
        var failDecryptWithTransient: Boolean = false,
        var failDecryptWithPermanentInvalidation: Boolean = false,
        var healthTemporarilyUnavailable: Boolean = false,
        var corruptDecryptOutputOnFirstRead: Boolean = false,
        var failNextDecryptWithWrongOutput: Boolean = false
    ) : AesGcmCipherProvider {
        private var secretKey: SecretKey? = null
        private val random = SecureRandom()
        private var decryptCallCount = 0

        private fun getOrGenerateKey(): SecretKey {
            secretKey?.let { return it }
            val gen = KeyGenerator.getInstance("AES")
            gen.init(256, random)
            return gen.generateKey().also { secretKey = it }
        }

        override fun encrypt(plaintext: ByteArray): ByteArray {
            if (failEncryptWithTransient) {
                throw KeystoreUnavailableException("Simulated transient Keystore encryption error")
            }
            val key = getOrGenerateKey()
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plaintext)
            return ByteBuffer.allocate(2 + iv.size + ciphertext.size).apply {
                put(1.toByte())
                put(iv.size.toByte())
                put(iv)
                put(ciphertext)
            }.array()
        }

        override fun decrypt(payload: ByteArray): ByteArray {
            decryptCallCount++
            if (failDecryptWithPermanentInvalidation) {
                throw KeystorePermanentInvalidationException("Simulated permanent Keystore key invalidation")
            }
            if (failDecryptWithTransient) {
                throw KeystoreUnavailableException("Simulated transient Keystore decryption error")
            }
            if ((corruptDecryptOutputOnFirstRead && decryptCallCount == 1) || failNextDecryptWithWrongOutput) {
                failNextDecryptWithWrongOutput = false
                return "{\"v\":1,\"iter\":120000,\"salt\":\"AAAA\",\"hash\":\"BBBB\"}".toByteArray(Charsets.UTF_8)
            }
            if (payload.size < 3 || payload[0] != 1.toByte()) {
                throw KeystoreUnavailableException("Malformed encrypted payload in test Keystore")
            }
            val key = secretKey ?: throw KeystorePermanentInvalidationException("Key missing in test Keystore")
            val ivLen = payload[1].toInt() and 0xFF
            if (ivLen <= 0 || payload.size <= 2 + ivLen) {
                throw KeystoreUnavailableException("Invalid IV length in test Keystore")
            }
            val iv = payload.copyOfRange(2, 2 + ivLen)
            val ct = payload.copyOfRange(2 + ivLen, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(ct)
        }

        override fun deleteKeyIfPresent() {
            secretKey = null
        }

        override fun checkKeyHealth(): KeyHealthStatus {
            return when {
                failDecryptWithPermanentInvalidation -> KeyHealthStatus.PERMANENTLY_UNAVAILABLE
                healthTemporarilyUnavailable -> KeyHealthStatus.TEMPORARILY_UNAVAILABLE
                secretKey == null -> KeyHealthStatus.NOT_CREATED
                else -> KeyHealthStatus.HEALTHY
            }
        }
    }

    private class FaultInjectingPinStorage(
        private val delegate: PinSecurityStorage,
        var failStagedWrite: Boolean = false,
        var corruptStagedReadback: Boolean = false,
        var failPromotion: Boolean = false,
        var partialCorruptBeforeFailPromotion: Boolean = false,
        var skipClearStagedOnPromotion: Boolean = false
    ) : PinSecurityStorage {
        override fun readEncryptedRecord(): String? = delegate.readEncryptedRecord()
        override fun writeEncryptedRecord(encodedBlob: String): Boolean = delegate.writeEncryptedRecord(encodedBlob)
        override fun clearEncryptedRecord(): Boolean = delegate.clearEncryptedRecord()

        override fun readStagedEncryptedRecord(): String? {
            if (corruptStagedReadback) {
                return Base64.encodeToString("corrupted-stage".toByteArray(), Base64.NO_WRAP)
            }
            return delegate.readStagedEncryptedRecord()
        }

        override fun writeStagedEncryptedRecord(encodedBlob: String): Boolean {
            if (failStagedWrite) return false
            return delegate.writeStagedEncryptedRecord(encodedBlob)
        }

        override fun clearStagedEncryptedRecord(): Boolean = delegate.clearStagedEncryptedRecord()

        override fun promoteStagedRecordToActive(): Boolean {
            if (failPromotion) {
                if (partialCorruptBeforeFailPromotion) {
                    delegate.writeEncryptedRecord("corrupt-partial-write")
                }
                return false
            }
            if (skipClearStagedOnPromotion) {
                val staged = delegate.readStagedEncryptedRecord() ?: return false
                return delegate.writeEncryptedRecord(staged)
            }
            return delegate.promoteStagedRecordToActive()
        }
    }

    private suspend fun awaitUncollectedUiStateCondition(
        vm: WealthViewModel,
        timeoutMs: Long = 5_000L,
        predicate: (WealthUiState) -> Boolean
    ): WealthUiState {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val current = vm.uiState.value
            if (predicate(current)) {
                return current
            }
            delay(10)
        }
        return vm.uiState.value
    }

    private class FakeBiometricAuthenticator(
        var capability: DeviceAuthCapability = DeviceAuthCapability.AVAILABLE,
        var nextOutcome: BiometricAuthOutcome = BiometricAuthOutcome.Succeeded
    ) : BiometricAuthenticator {
        var authRequestCount: Int = 0
            private set

        override fun checkDeviceAuthCapability(): DeviceAuthCapability = capability

        override fun authenticate(
            title: String,
            subtitle: String,
            description: String,
            onResult: (BiometricAuthOutcome) -> Unit
        ) {
            authRequestCount++
            onResult(nextOutcome)
        }
    }

    private fun openTempDb(name: String): AppDatabase {
        val dbFile: File = tempFolder.newFile(name).apply { delete() }
        return Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .addCallback(AppDatabase.createCallback())
            .allowMainThreadQueries()
            .build()
            .also { openedDatabases.add(it) }
    }

    private fun createIsolatedPrefsStorage(prefsName: String): SharedPrefsPinSecurityStorage {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        return SharedPrefsPinSecurityStorage(context, prefsName)
    }

    private suspend fun seedSyntheticPortfolioData(db: AppDatabase) {
        db.transactionDao().insertTransaction(
            TransactionEntity(
                id = 0,
                datePersian = "1404/10/12",
                timestamp = 1735700000000L,
                assetClass = AssetClass.GOLD,
                assetName = "طلای ۱۸ عیار",
                assetSymbol = "GOLD18",
                action = TransactionAction.BUY,
                quantity = 2.5,
                unit = "گرم",
                unitPrice = 4_800_000.0,
                currency = CurrencyType.TOMAN,
                fees = 12_000.0,
                commissionType = CommissionType.FIXED,
                commissionRate = 12_000.0,
                totalAmount = 12_012_000.0,
                brokerOrSource = "طلافروشی آزمایشی",
                notes = "داده مصنوعی آزمون"
            )
        )
        db.currentPriceDao().insertPrice(
            CurrentPriceEntity(
                assetSymbolOrName = "طلای ۱۸ عیار",
                assetName = "طلای ۱۸ عیار",
                assetClass = AssetClass.GOLD,
                price = 5_100_000.0,
                currency = CurrencyType.TOMAN,
                source = "TGJU",
                lastUpdated = 1735800000000L,
                unit = "گرم",
                instrumentId = "GERAM18"
            )
        )
        db.goalDao().insertGoal(
            GoalEntity(
                title = "صندوق اضطراری آزمایشی",
                targetAmountToman = 300_000_000.0,
                targetDatePersian = "1405/12/29",
                category = "نقدینگی"
            )
        )
        db.liabilityDao().insertLiability(
            LiabilityEntity(
                title = "تسهیلات آزمایشی",
                totalAmountToman = 80_000_000.0,
                monthlyPaymentToman = 3_500_000.0,
                dueDatePersian = "1406/05/01"
            )
        )
        db.dividendDao().insertDividend(
            DividendEntity(
                stockSymbol = "فولاد",
                stockName = "فولاد مبارکه",
                sharesHeld = 1000.0,
                dpsToman = 140.0,
                receivableAmountToman = 140_000.0,
                meetingDatePersian = "1404/04/18",
                paymentDatePersian = "1404/09/10",
                receivedAmountToman = 0.0,
                isPaid = false
            )
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        openedDatabases.forEach { db ->
            if (db.isOpen) db.close()
        }
        openedDatabases.clear()
    }

    // 1 & 2. New PIN creation, verification, Persian digit support, unique salt, and wrong PIN rejection
    @Test
    fun pinCreationAndVerification_acceptsCorrectAndRejectsIncorrectPin() = runBlocking {
        val db = openTempDb("pin_create_verify.db")
        val cipher = TestAesGcmCipherProvider()
        val storage = createIsolatedPrefsStorage("prefs_create_verify")
        val manager = PinSecurityManager(storage, cipher)
        val repo = WealthRepository(db, context, pinSecurityManager = manager)

        val setRes = repo.setOrChangePin(currentPin = null, newPin = "۴۸۲۹")
        assertTrue(setRes is PinMutationResult.Success)
        assertTrue(repo.hasSecurePin())
        assertTrue(repo.getSettings().isPinEnabled)

        assertTrue(repo.verifyPin("4829") is PinVerificationResult.Success)
        assertTrue(repo.verifyPin("۴۸۲۹") is PinVerificationResult.Success)
        assertTrue(repo.verifyPin("4828") is PinVerificationResult.InvalidPin)
        assertTrue(repo.verifyPin("") is PinVerificationResult.InvalidPin)

        // Setting the same PIN again generates a fresh random salt & different encrypted blob
        val firstBlob = storage.readEncryptedRecord()
        val changeRes = repo.setOrChangePin(currentPin = "4829", newPin = "4829")
        assertTrue(changeRes is PinMutationResult.Success)
        val secondBlob = storage.readEncryptedRecord()
        assertNotNull(firstBlob)
        assertNotNull(secondBlob)
        assertNotEquals(firstBlob, secondBlob)
        assertTrue(repo.verifyPin("4829") is PinVerificationResult.Success)
    }

    // 3, 6 & 7. Legacy plaintext PIN migration + readback verification + idempotency + zero plaintext remaining
    @Test
    fun legacyPinMigration_migratesOncePreservesSettingsAndLeavesZeroPlaintext() = runBlocking {
        val db = openTempDb("pin_legacy_migrate.db")
        val legacySettings = InitialData.defaultSettings().copy(
            displayCurrency = CurrencyType.RIAL,
            monthlyInvestmentToman = 18_500_000.0,
            creatorName = "کاربر تست مهاجرت",
            pinCode = "1379",
            isPinEnabled = true,
            isBiometricEnabled = true,
            hideSensitiveAmounts = true
        )
        db.settingsDao().saveSettings(legacySettings)

        val prefsName = "prefs_legacy_migrate"
        val storage = createIsolatedPrefsStorage(prefsName)
        val cipher = TestAesGcmCipherProvider()
        val manager = PinSecurityManager(storage, cipher)
        val repo = WealthRepository(db, context, pinSecurityManager = manager)

        val mig1 = repo.migrateLegacyPinIfNeeded()
        assertTrue(mig1 is PinMigrationResult.MigratedAndVerified)

        // Room plaintext pinCode cleared, while all other settings intact
        val afterSettings = db.settingsDao().getSettingsDirect()!!
        assertEquals("", afterSettings.pinCode)
        assertTrue(afterSettings.isPinEnabled)
        assertTrue(afterSettings.isBiometricEnabled)
        assertTrue(afterSettings.hideSensitiveAmounts)
        assertEquals(CurrencyType.RIAL, afterSettings.displayCurrency)
        assertEquals(18_500_000.0, afterSettings.monthlyInvestmentToman, 0.0)
        assertEquals("کاربر تست مهاجرت", afterSettings.creatorName)

        // Verify raw SharedPreferences does not store plaintext PIN, salt, or hash
        val rawPrefValue = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(SharedPrefsPinSecurityStorage.KEY_ENCRYPTED_PIN_RECORD, "")!!
        assertTrue(rawPrefValue.isNotBlank())
        assertFalse(rawPrefValue.contains("1379"))
        assertFalse(rawPrefValue.contains("iter"))
        assertFalse(rawPrefValue.contains("salt"))
        assertFalse(rawPrefValue.contains("hash"))
        // Even Base64-decoded payload is encrypted AES-GCM binary, not readable JSON
        val decodedString = String(Base64.decode(rawPrefValue, Base64.NO_WRAP), Charsets.UTF_8)
        assertFalse(decodedString.contains("1379"))
        assertFalse(decodedString.contains("salt"))

        // Migrated verifier unlocks cleanly with legacy PIN
        assertTrue(repo.verifyPin("1379") is PinVerificationResult.Success)
        assertTrue(repo.verifyPin("0000") is PinVerificationResult.InvalidPin)

        // Change PIN after migration, then run repeated startup migrations -> must be idempotent
        assertTrue(repo.setOrChangePin(currentPin = "1379", newPin = "9517") is PinMutationResult.Success)
        val blobBeforeRepeat = storage.readEncryptedRecord()
        assertTrue(repo.migrateLegacyPinIfNeeded() is PinMigrationResult.NotNeeded)
        assertTrue(repo.migrateLegacyPinIfNeeded() is PinMigrationResult.NotNeeded)
        assertEquals(blobBeforeRepeat, storage.readEncryptedRecord())
        assertTrue(repo.verifyPin("9517") is PinVerificationResult.Success)
        assertTrue(repo.verifyPin("1379") is PinVerificationResult.InvalidPin)
    }

    // 4 & 5. Readback verification before deletion + Keystore failure preserves legacy PIN and fails closed
    @Test
    fun legacyMigration_readbackOrKeystoreFailurePreservesLegacyPinAndFailsClosed() = runBlocking {
        // Case A: Cipher readback returns mismatched payload -> legacy PIN must NOT be deleted
        val dbA = openTempDb("pin_readback_fail.db")
        dbA.settingsDao().saveSettings(
            InitialData.defaultSettings().copy(pinCode = "2468", isPinEnabled = true)
        )
        val storageA = createIsolatedPrefsStorage("prefs_readback_fail")
        val cipherA = TestAesGcmCipherProvider(corruptDecryptOutputOnFirstRead = true)
        val managerA = PinSecurityManager(storageA, cipherA)
        val resA = managerA.migrateLegacyPinIfNeeded(dbA.settingsDao())

        assertTrue(resA is PinMigrationResult.FailedPreservedLegacy)
        val settingsA = dbA.settingsDao().getSettingsDirect()!!
        assertEquals("2468", settingsA.pinCode)
        assertTrue(settingsA.isPinEnabled)
        assertFalse(managerA.hasSecurePin())

        // Case B: Keystore throws permanent invalidation during encryption/storage -> legacy PIN preserved, fails closed
        val dbB = openTempDb("pin_keystore_fail.db")
        dbB.settingsDao().saveSettings(
            InitialData.defaultSettings().copy(pinCode = "3579", isPinEnabled = true)
        )
        val storageB = createIsolatedPrefsStorage("prefs_keystore_fail")
        val cipherB = TestAesGcmCipherProvider(failEncryptWithTransient = true)
        val repoB = WealthRepository(dbB, context, pinSecurityManager = PinSecurityManager(storageB, cipherB))

        val resB = repoB.migrateLegacyPinIfNeeded()
        assertTrue(resB is PinMigrationResult.FailedPreservedLegacy)
        val settingsB = dbB.settingsDao().getSettingsDirect()!!
        assertEquals("3579", settingsB.pinCode)
        assertTrue(settingsB.isPinEnabled)

        // Even during price sync or settings save while Keystore failed, legacy PIN is preserved (not wiped!)
        repoB.saveSettings(settingsB.copy(lastPriceUpdateTimestamp = 123456789L))
        assertEquals("3579", dbB.settingsDao().getSettingsDirect()!!.pinCode)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun permanentKeystoreFailure_failsClosedInViewModelUntilExplicitRecovery() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val db = openTempDb("vm_keystore_fail.db")
            seedSyntheticPortfolioData(db)
            val storage = createIsolatedPrefsStorage("prefs_vm_keystore_fail")
            val cipher = TestAesGcmCipherProvider()
            val manager = PinSecurityManager(storage, cipher)
            val repo = WealthRepository(db, context, pinSecurityManager = manager)

            assertTrue(repo.setOrChangePin(null, "7788") is PinMutationResult.Success)
            // Simulate Keystore permanent invalidation (e.g. OS security reset)
            cipher.failDecryptWithPermanentInvalidation = true

            val vm = WealthViewModel(repo)
            val readyState = awaitUncollectedUiStateCondition(vm) { it.isSecurityReady }

            assertTrue(readyState.isSecurityReady)
            assertTrue(readyState.isAppLocked)
            assertTrue(readyState.isKeystorePermanentlyUnavailable)
            assertNotNull(readyState.pinErrorMessage)

            // Cannot bypass lock with a wrong or old PIN when Keystore is permanently unavailable
            vm.unlockWithPin("7788")
            delay(30)
            assertTrue(vm.uiState.value.isAppLocked)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // 8 & 9. Recovery requires BOTH device auth and explicit user confirmation, and preserves ALL financial records
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pinRecovery_requiresDeviceAuthAndExplicitConfirmation_andPreservesAllFinancialData() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val db = openTempDb("pin_recovery_preserve.db")
            seedSyntheticPortfolioData(db)
            db.settingsDao().saveSettings(
                InitialData.defaultSettings().copy(
                    monthlyInvestmentToman = 42_000_000.0,
                    displayCurrency = CurrencyType.RIAL,
                    creatorName = "سرمایه‌گذار واقعی"
                )
            )
            val storage = createIsolatedPrefsStorage("prefs_recovery_preserve")
            val cipher = TestAesGcmCipherProvider()
            val manager = PinSecurityManager(storage, cipher)
            val repo = WealthRepository(db, context, pinSecurityManager = manager)
            assertTrue(repo.setOrChangePin(null, "5566") is PinMutationResult.Success)

            // Repository guard rejects unauthenticated or unconfirmed reset
            assertTrue(
                repo.resetPinGateAfterConfirmedRecovery(
                    isDeviceAuthenticated = false,
                    isUserConfirmed = true
                ) is ValidationResult.Error
            )
            assertTrue(
                repo.resetPinGateAfterConfirmedRecovery(
                    isDeviceAuthenticated = true,
                    isUserConfirmed = false
                ) is ValidationResult.Error
            )
            assertTrue(repo.getSettings().isPinEnabled)

            val vm = WealthViewModel(repo)
            val initialReady = awaitUncollectedUiStateCondition(vm) { it.isSecurityReady }
            assertTrue(initialReady.isSecurityReady)
            assertTrue(initialReady.isAppLocked)

            // Attempt confirm before device auth -> must fail closed!
            vm.confirmResetPinGateAfterRecovery()
            delay(30)
            assertTrue(vm.uiState.value.isAppLocked)
            assertTrue(repo.getSettings().isPinEnabled)

            // Step 1: Device auth succeeds -> waiting for explicit confirmation
            vm.onRecoveryDeviceAuthSucceeded()
            val awaitingConfirm = awaitUncollectedUiStateCondition(vm) { it.pinRecoveryAwaitingConfirmation }
            assertTrue(awaitingConfirm.pinRecoveryAwaitingConfirmation)
            assertTrue(awaitingConfirm.isAppLocked)
            assertTrue(repo.getSettings().isPinEnabled)

            // User cancels recovery -> stays locked & PIN stays enabled
            vm.cancelPinRecovery()
            val canceled = awaitUncollectedUiStateCondition(vm) { !it.pinRecoveryAwaitingConfirmation }
            assertFalse(canceled.pinRecoveryAwaitingConfirmation)
            assertTrue(canceled.isAppLocked)
            assertTrue(repo.getSettings().isPinEnabled)

            // Step 2: Re-authenticate and explicitly confirm recovery
            vm.onRecoveryDeviceAuthSucceeded()
            awaitUncollectedUiStateCondition(vm) { it.pinRecoveryAwaitingConfirmation }
            vm.confirmResetPinGateAfterRecovery()
            val unlocked = awaitUncollectedUiStateCondition(vm) { !it.isAppLocked }

            assertFalse(unlocked.isAppLocked)
            assertFalse(unlocked.pinRecoveryAwaitingConfirmation)
            assertFalse(repo.getSettings().isPinEnabled)
            assertFalse(repo.hasSecurePin())

            // Verify 100% of all financial data & settings remained intact!
            assertEquals(1, db.transactionDao().getAllTransactions().size)
            assertEquals(4_800_000.0, db.transactionDao().getAllTransactions().first().unitPrice!!, 0.0)
            assertEquals(1, db.currentPriceDao().getAllPrices().size)
            assertEquals(5_100_000.0, db.currentPriceDao().getAllPrices().first().price, 0.0)
            assertEquals(1, db.goalDao().getAllGoals().size)
            assertEquals("صندوق اضطراری آزمایشی", db.goalDao().getAllGoals().first().title)
            assertEquals(1, db.liabilityDao().getAllLiabilities().size)
            assertEquals(80_000_000.0, db.liabilityDao().getAllLiabilities().first().totalAmountToman, 0.0)
            assertEquals(1, db.dividendDao().getAllDividends().size)
            assertEquals(140_000.0, db.dividendDao().getAllDividends().first().receivableAmountToman, 0.0)

            val preservedSettings = db.settingsDao().getSettingsDirect()!!
            assertEquals(42_000_000.0, preservedSettings.monthlyInvestmentToman, 0.0)
            assertEquals(CurrencyType.RIAL, preservedSettings.displayCurrency)
            assertEquals("سرمایه‌گذار واقعی", preservedSettings.creatorName)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // 10. Backup exports never include PINs/salts/hashes/secrets AND AutoBackup/DataExtraction XML exclude DB + PIN prefs
    @Test
    fun backupExportAndSystemBackupRules_excludePinSecretsAndDatabase() = runBlocking {
        val db = openTempDb("pin_backup_exclusion.db")
        seedSyntheticPortfolioData(db)
        val storage = createIsolatedPrefsStorage("prefs_backup_exclusion")
        val cipher = TestAesGcmCipherProvider()
        val repo = WealthRepository(db, context, pinSecurityManager = PinSecurityManager(storage, cipher))

        assertTrue(repo.setOrChangePin(null, "9182") is PinMutationResult.Success)
        val exportedJson = repo.exportBackupJson()

        assertFalse(exportedJson.contains("9182"))
        assertFalse(exportedJson.contains("pinCode"))
        assertFalse(exportedJson.contains("isPinEnabled"))
        assertFalse(exportedJson.contains("encrypted_pin_record"))
        assertFalse(exportedJson.contains("hash"))
        assertFalse(exportedJson.contains("salt"))
        assertFalse(exportedJson.contains("app_settings"))

        // Verify res/xml/backup_rules.xml and res/xml/data_extraction_rules.xml exclude database and pin_security_prefs.xml
        val backupXmlParser = context.resources.getXml(R.xml.backup_rules)
        val backupExcluded = mutableListOf<Pair<String, String>>()
        var event = backupXmlParser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && backupXmlParser.name == "exclude") {
                val domain = backupXmlParser.getAttributeValue(null, "domain").orEmpty()
                val path = backupXmlParser.getAttributeValue(null, "path").orEmpty()
                backupExcluded.add(domain to path)
            }
            event = backupXmlParser.next()
        }
        assertTrue(backupExcluded.contains("database" to "."))
        assertTrue(backupExcluded.contains("sharedpref" to "${SharedPrefsPinSecurityStorage.PREFS_FILE_NAME}.xml"))

        val extractionXmlParser = context.resources.getXml(R.xml.data_extraction_rules)
        val cloudExcluded = mutableListOf<Pair<String, String>>()
        val transferExcluded = mutableListOf<Pair<String, String>>()
        var currentSection = ""
        var ev = extractionXmlParser.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (extractionXmlParser.name) {
                    "cloud-backup", "device-transfer" -> currentSection = extractionXmlParser.name
                    "exclude" -> {
                        val domain = extractionXmlParser.getAttributeValue(null, "domain").orEmpty()
                        val path = extractionXmlParser.getAttributeValue(null, "path").orEmpty()
                        if (currentSection == "cloud-backup") cloudExcluded.add(domain to path)
                        if (currentSection == "device-transfer") transferExcluded.add(domain to path)
                    }
                }
            } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                if (extractionXmlParser.name == "cloud-backup" || extractionXmlParser.name == "device-transfer") {
                    currentSection = ""
                }
            }
            ev = extractionXmlParser.next()
        }
        assertTrue(cloudExcluded.contains("database" to "."))
        assertTrue(cloudExcluded.contains("sharedpref" to "${SharedPrefsPinSecurityStorage.PREFS_FILE_NAME}.xml"))
        assertTrue(transferExcluded.contains("database" to "."))
        assertTrue(transferExcluded.contains("sharedpref" to "${SharedPrefsPinSecurityStorage.PREFS_FILE_NAME}.xml"))
    }

    // 11. Biometric PromptInfo builds safely without InvalidArgumentException on API < 30 & API >= 30
    @Test
    fun biometricPromptInfo_buildsSafelyForApi28AndApi30Plus() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val authenticator = AndroidBiometricAuthenticator(activity)

        val preRPrompt = authenticator.buildPromptInfo(
            title = "تست",
            subtitle = "زیرعنوان",
            description = "توضیح",
            sdkInt = Build.VERSION_CODES.P
        )
        assertNotNull(preRPrompt)

        val api30Prompt = authenticator.buildPromptInfo(
            title = "تست",
            subtitle = "زیرعنوان",
            description = "توضیح",
            sdkInt = Build.VERSION_CODES.R
        )
        assertNotNull(api30Prompt)
    }

    // UI Tests: PinLockScreen explicit confirmation dialog + wrong PIN banner + MoreScreen security card
    @Test
    fun pinLockScreenUi_rendersErrorRecoveryConfirmationAndMoreScreenSecurityCard() {
        val lockedState = WealthUiState(
            settings = InitialData.defaultSettings().copy(isPinEnabled = true, isBiometricEnabled = true),
            isAppLocked = true,
            isSecurityReady = true,
            pinErrorMessage = "رمز ورود اشتباه است.",
            pinRecoveryAwaitingConfirmation = true
        )

        var confirmClicked = false
        var cancelClicked = false
        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    PinLockScreen(
                        uiState = lockedState,
                        canUseDeviceAuth = true,
                        onUnlockWithPin = {},
                        onTriggerBiometricUnlock = {},
                        onTriggerPinRecovery = {},
                        onConfirmPinRecovery = { confirmClicked = true },
                        onCancelPinRecovery = { cancelClicked = true }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("pin_lock_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pin_lock_error_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pin_recovery_confirm_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_pin_recovery_btn").performClick()
        assertTrue(confirmClicked)
        composeTestRule.onNodeWithTag("cancel_pin_recovery_btn").performClick()
        assertTrue(cancelClicked)
    }

    @Test
    fun moreScreen_displaysPinSecurityCardAndSetupDialog() {
        val settings = InitialData.defaultSettings()
        val uiState = WealthUiState(
            settings = settings,
            portfolioSummary = CalculationEngine.calculatePortfolio(emptyList(), emptyList(), settings, emptyList())
        )
        composeTestRule.setContent {
            ModiriatSarmayeTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MoreScreen(
                        uiState = uiState,
                        onSaveSettings = {},
                        onExportBackup = { "{}" },
                        onRestoreBackup = { _, _ -> },
                        onSyncMarketPrices = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("more_screen_list")
            .performScrollToNode(hasTestTag("pin_security_card"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("pin_security_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("setup_pin_btn").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pin_setup_dialog").assertIsDisplayed()
    }

    // FIX 1 TESTS: Eager uiState without collector, fail-closed unresolved splash, and dashboard visibility when PIN disabled
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun wealthViewModel_uiStateUpdatesWithoutCollectorAndExposesDashboardOnlyWhenReadyAndUnlocked() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val db = openTempDb("vm_eager_no_collector.db")
            seedSyntheticPortfolioData(db)
            db.settingsDao().saveSettings(
                InitialData.defaultSettings().copy(
                    priceUpdateFrequency = PriceUpdateFrequency.MANUAL_ONLY,
                    isPinEnabled = false
                )
            )
            val storage = createIsolatedPrefsStorage("prefs_vm_eager")
            val cipher = TestAesGcmCipherProvider()
            val repo = WealthRepository(db, context, pinSecurityManager = PinSecurityManager(storage, cipher))

            val vm = WealthViewModel(repo)
            // Immediately after construction, unresolved state must fail closed
            assertFalse(vm.uiState.value.isSecurityReady)
            assertTrue(vm.uiState.value.isAppLocked)

            // Poll vm.uiState.value WITHOUT registering any coroutine collector on uiState
            val ready = awaitUncollectedUiStateCondition(vm) { it.isSecurityReady }
            assertTrue(ready.isSecurityReady)
            assertFalse(ready.isAppLocked)
            assertEquals(1, ready.transactions.size)

            // Render WealthAppContent and confirm dashboard becomes visible when PIN is disabled
            composeTestRule.setContent {
                ModiriatSarmayeTheme {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        WealthAppContent(viewModel = vm)
                    }
                }
            }
            composeTestRule.waitUntil(timeoutMillis = 5_000L) {
                composeTestRule.onAllNodesWithTag("dashboard_screen")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()
            composeTestRule.onNodeWithTag("security_checking_splash").assertDoesNotExist()
            composeTestRule.onNodeWithTag("pin_lock_screen").assertDoesNotExist()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun wealthAppContent_hidesFinancialUiWhileSecurityIsUnresolved() {
        val unresolvedState = WealthUiState(
            transactions = listOf(
                TransactionEntity(
                    datePersian = "1404/10/12",
                    timestamp = 1735700000000L,
                    assetClass = AssetClass.GOLD,
                    assetName = "طلای ۱۸ عیار",
                    assetSymbol = "GOLD18",
                    action = TransactionAction.BUY,
                    quantity = 2.5,
                    unit = "گرم",
                    unitPrice = 4_800_000.0
                )
            ),
            isSecurityReady = false,
            isAppLocked = true
        )

        val db = openTempDb("vm_unresolved_gate.db")
        runBlocking {
            db.settingsDao().saveSettings(
                InitialData.defaultSettings().copy(pinCode = "1234", isPinEnabled = true)
            )
        }
        val gate = CompletableDeferred<Unit>()
        val blockingStorage = object : PinSecurityStorage {
            override fun readEncryptedRecord(): String? = null
            override fun writeEncryptedRecord(encodedBlob: String): Boolean = false
            override fun clearEncryptedRecord(): Boolean = true
            override fun readStagedEncryptedRecord(): String? = null
            override fun writeStagedEncryptedRecord(encodedBlob: String): Boolean = false
            override fun clearStagedEncryptedRecord(): Boolean = run {
                // Keep migration/check unresolved until gate opens? Notice migrateLegacyPinIfNeeded runs on viewModelScope
                true
            }
            override fun promoteStagedRecordToActive(): Boolean = false
        }
        // Verify unresolved WealthUiState in SecurityCheckingSplashScreen / Lock gating directly
        assertFalse(unresolvedState.isSecurityReady)
        assertTrue(unresolvedState.isAppLocked)

        val blockedCipher = object : AesGcmCipherProvider {
            override fun encrypt(plaintext: ByteArray): ByteArray {
                runBlocking { gate.await() }
                throw KeystoreUnavailableException("Simulated wait")
            }
            override fun decrypt(payload: ByteArray): ByteArray = throw KeystoreUnavailableException("wait")
            override fun deleteKeyIfPresent() = Unit
            override fun checkKeyHealth(): KeyHealthStatus = KeyHealthStatus.TEMPORARILY_UNAVAILABLE
        }
        val blockingRepo = WealthRepository(
            database = db,
            context = context,
            pinSecurityManager = PinSecurityManager(blockingStorage, blockedCipher)
        )
        val blockingVm = WealthViewModel(blockingRepo)

        try {
            composeTestRule.setContent {
                ModiriatSarmayeTheme {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        WealthAppContent(viewModel = blockingVm)
                    }
                }
            }
            composeTestRule.waitForIdle()

            // While security initialization is unresolved, only checking splash is visible; financial dashboard is absent
            composeTestRule.onNodeWithTag("security_checking_splash").assertIsDisplayed()
            composeTestRule.onNodeWithTag("dashboard_screen").assertDoesNotExist()
            composeTestRule.onNodeWithTag("bottom_nav_bar").assertDoesNotExist()
            composeTestRule.onNodeWithTag("pin_lock_screen").assertDoesNotExist()
        } finally {
            gate.complete(Unit)
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            composeTestRule.onAllNodesWithTag("pin_lock_screen")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag("pin_lock_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("dashboard_screen").assertDoesNotExist()
    }

    // FIX 2 TESTS: Crash-safe staged PIN update, rollback on write/readback/promotion failure, interruption recovery, and zero plaintext
    @Test
    fun stagedPinChange_preservesActivePinAcrossAllFailureModesAndCompletesCleanly() = runBlocking {
        val prefsName = "prefs_staged_rollback"
        val baseStorage = createIsolatedPrefsStorage(prefsName)
        val faultStorage = FaultInjectingPinStorage(baseStorage)
        val cipher = TestAesGcmCipherProvider()
        val manager = PinSecurityManager(faultStorage, cipher)

        // Initial PIN = 1122
        assertTrue(manager.setPin("1122") is PinMutationResult.Success)
        val initialActiveBlob = baseStorage.readEncryptedRecord()
        assertNotNull(initialActiveBlob)
        assertNull(baseStorage.readStagedEncryptedRecord())

        // 1. Failed staged write preserves active 1122
        faultStorage.failStagedWrite = true
        val failWriteRes = manager.setPin("3344")
        assertTrue(failWriteRes is PinMutationResult.KeystoreError)
        assertEquals(initialActiveBlob, baseStorage.readEncryptedRecord())
        assertNull(baseStorage.readStagedEncryptedRecord())
        assertTrue(manager.verifyPin("1122") is PinVerificationResult.Success)
        assertTrue(manager.verifyPin("3344") is PinVerificationResult.InvalidPin)
        faultStorage.failStagedWrite = false

        // 2. Failed staged readback (storage corruption) preserves active 1122 and cleans staged entry
        faultStorage.corruptStagedReadback = true
        val failReadbackRes = manager.setPin("3344")
        assertTrue(failReadbackRes is PinMutationResult.KeystoreError)
        assertEquals(initialActiveBlob, baseStorage.readEncryptedRecord())
        assertNull(baseStorage.readStagedEncryptedRecord())
        assertTrue(manager.verifyPin("1122") is PinVerificationResult.Success)
        assertTrue(manager.verifyPin("3344") is PinVerificationResult.InvalidPin)
        faultStorage.corruptStagedReadback = false

        // 3. Cipher decrypt mismatch during staged readback preserves active 1122 with the same active key
        cipher.failNextDecryptWithWrongOutput = true
        assertTrue(manager.setPin("5566") is PinMutationResult.KeystoreError)
        assertEquals(initialActiveBlob, baseStorage.readEncryptedRecord())
        assertNull(baseStorage.readStagedEncryptedRecord())
        assertTrue(manager.verifyPin("1122") is PinVerificationResult.Success)
        assertTrue(manager.verifyPin("5566") is PinVerificationResult.InvalidPin)

        // 4. Failed promotion preserves active 1122 (both clean failure and partial corruption rollback)
        faultStorage.failPromotion = true
        faultStorage.partialCorruptBeforeFailPromotion = true
        val failPromoteRes = manager.setPin("7788")
        assertTrue(failPromoteRes is PinMutationResult.KeystoreError)
        assertEquals(initialActiveBlob, baseStorage.readEncryptedRecord())
        assertNull(baseStorage.readStagedEncryptedRecord())
        assertTrue(manager.verifyPin("1122") is PinVerificationResult.Success)
        assertTrue(manager.verifyPin("7788") is PinVerificationResult.InvalidPin)
        faultStorage.failPromotion = false
        faultStorage.partialCorruptBeforeFailPromotion = false

        // 5. Simulate interrupted PIN change: staged record exists with encrypted blob while active still holds 1122
        val stagedNewBlob = Base64.encodeToString(
            cipher.encrypt("{\"v\":1}".toByteArray()),
            Base64.NO_WRAP
        )
        assertTrue(baseStorage.writeStagedEncryptedRecord(stagedNewBlob))
        assertFalse(stagedNewBlob.contains("1122"))
        // Verify no plaintext PIN stored in staged prefs
        val rawPrefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all
        rawPrefs.values.forEach { v ->
            assertFalse(v.toString().contains("1122"))
            assertFalse(v.toString().contains("7788"))
        }

        // Restart with fresh manager & run startup check: active 1122 still unlocks and orphaned staged entry is cleaned
        val db = openTempDb("pin_staged_restart.db")
        db.settingsDao().saveSettings(InitialData.defaultSettings().copy(isPinEnabled = true, pinCode = ""))
        val restartedManager = PinSecurityManager(SharedPrefsPinSecurityStorage(context, prefsName), cipher)
        assertTrue(restartedManager.migrateLegacyPinIfNeeded(db.settingsDao()) is PinMigrationResult.NotNeeded)
        assertNull(baseStorage.readStagedEncryptedRecord())
        assertTrue(restartedManager.verifyPin("1122") is PinVerificationResult.Success)

        // 6. Successful staged PIN change activates new PIN 7788 and invalidates previous PIN 1122
        assertTrue(restartedManager.setPin("7788") is PinMutationResult.Success)
        assertNull(baseStorage.readStagedEncryptedRecord())
        assertTrue(restartedManager.verifyPin("7788") is PinVerificationResult.Success)
        assertTrue(restartedManager.verifyPin("1122") is PinVerificationResult.InvalidPin)

        // 7. Repeated PIN changes remain safe
        assertTrue(restartedManager.setPin("8899") is PinMutationResult.Success)
        assertTrue(restartedManager.setPin("9900") is PinMutationResult.Success)
        assertTrue(restartedManager.verifyPin("9900") is PinVerificationResult.Success)
        assertTrue(restartedManager.verifyPin("8899") is PinVerificationResult.InvalidPin)
        assertNull(baseStorage.readStagedEncryptedRecord())
    }

    // FIX 3 TESTS: Permanent vs temporary Keystore error classification
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun keystoreErrorClassification_distinguishesPermanentVsTemporaryFailures() = runBlocking {
        val provider = AndroidKeystoreAesGcmProvider("test_alias")
        assertEquals(
            KeyHealthStatus.PERMANENTLY_UNAVAILABLE,
            provider.classifyKeyHealthException(KeyPermanentlyInvalidatedException("invalidated"))
        )
        assertEquals(
            KeyHealthStatus.PERMANENTLY_UNAVAILABLE,
            provider.classifyKeyHealthException(UnrecoverableKeyException("unrecoverable"))
        )
        assertEquals(
            KeyHealthStatus.PERMANENTLY_UNAVAILABLE,
            provider.classifyKeyHealthException(UnrecoverableEntryException("entry"))
        )
        assertEquals(
            KeyHealthStatus.PERMANENTLY_UNAVAILABLE,
            provider.classifyKeyHealthException(RuntimeException(UnrecoverableKeyException("wrapped")))
        )
        assertEquals(
            KeyHealthStatus.TEMPORARILY_UNAVAILABLE,
            provider.classifyKeyHealthException(ProviderException("transient keymaster error"))
        )
        assertEquals(
            KeyHealthStatus.TEMPORARILY_UNAVAILABLE,
            provider.classifyKeyHealthException(KeyStoreException("temporary store error"))
        )
        assertEquals(
            KeyHealthStatus.TEMPORARILY_UNAVAILABLE,
            provider.classifyKeyHealthException(IllegalStateException("unknown transient state"))
        )

        // Verify PinSecurityManager & WealthViewModel treat temporary unavailability as recoverable (not permanent invalidation)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val db = openTempDb("pin_temp_keystore_health.db")
            val storage = createIsolatedPrefsStorage("prefs_temp_keystore_health")
            val cipher = TestAesGcmCipherProvider()
            val manager = PinSecurityManager(storage, cipher)
            val repo = WealthRepository(db, context, pinSecurityManager = manager)

            assertTrue(repo.setOrChangePin(null, "4321") is PinMutationResult.Success)
            val activeRecordBefore = storage.readEncryptedRecord()
            cipher.healthTemporarilyUnavailable = true

            assertFalse(manager.isKeystorePermanentlyUnavailable())
            assertEquals(KeyHealthStatus.TEMPORARILY_UNAVAILABLE, manager.checkKeystoreHealth())

            val vm = WealthViewModel(repo)
            val state = awaitUncollectedUiStateCondition(vm) { it.isSecurityReady }
            assertTrue(state.isAppLocked)
            assertFalse(state.isKeystorePermanentlyUnavailable)
            assertNotNull(state.pinErrorMessage)
            // Original key & encrypted record untouched (no replacement key created)
            assertEquals(activeRecordBefore, storage.readEncryptedRecord())
            // Recovering from temporary error allows normal PIN unlock
            cipher.healthTemporarilyUnavailable = false
            vm.unlockWithPin("4321")
            val unlocked = awaitUncollectedUiStateCondition(vm) { !it.isAppLocked }
            assertFalse(unlocked.isAppLocked)

            // Genuinely missing key (NOT_CREATED) when encrypted PIN record exists IS permanent invalidation
            cipher.deleteKeyIfPresent()
            assertEquals(KeyHealthStatus.NOT_CREATED, manager.checkKeystoreHealth())
            assertTrue(manager.isKeystorePermanentlyUnavailable())
        } finally {
            Dispatchers.resetMain()
        }
    }

    // FIX 4 TESTS: Prevent PIN verification bypass when isPinEnabled == true across all states
    @Test
    fun setOrChangePin_preventsVerificationBypassAcrossSecureLegacyAndMissingStates() = runBlocking {
        // Case A: Enabled PIN with existing secure record
        val dbA = openTempDb("pin_bypass_secure.db")
        val storageA = createIsolatedPrefsStorage("prefs_bypass_secure")
        val cipherA = TestAesGcmCipherProvider()
        val repoA = WealthRepository(dbA, context, pinSecurityManager = PinSecurityManager(storageA, cipherA))
        assertTrue(repoA.setOrChangePin(null, "1234") is PinMutationResult.Success)
        assertTrue(repoA.setOrChangePin(null, "9999") is PinMutationResult.ValidationError)
        assertTrue(repoA.setOrChangePin("0000", "9999") is PinMutationResult.ValidationError)
        assertTrue(repoA.verifyPin("1234") is PinVerificationResult.Success)

        // Case B: Enabled PIN with legacy plaintext PIN and NO secure record
        val dbB = openTempDb("pin_bypass_legacy.db")
        dbB.settingsDao().saveSettings(
            InitialData.defaultSettings().copy(pinCode = "2468", isPinEnabled = true)
        )
        val storageB = createIsolatedPrefsStorage("prefs_bypass_legacy")
        val cipherB = TestAesGcmCipherProvider(failEncryptWithTransient = true)
        val repoB = WealthRepository(dbB, context, pinSecurityManager = PinSecurityManager(storageB, cipherB))

        // Must reject null or incorrect currentPin without modifying legacy PIN
        assertTrue(repoB.setOrChangePin(null, "9999") is PinMutationResult.ValidationError)
        assertTrue(repoB.setOrChangePin("1111", "9999") is PinMutationResult.ValidationError)
        assertEquals("2468", dbB.settingsDao().getSettingsDirect()!!.pinCode)
        assertTrue(dbB.settingsDao().getSettingsDirect()!!.isPinEnabled)

        // With correct currentPin while Keystore fails -> preserves legacy PIN & stays enabled
        assertTrue(repoB.setOrChangePin("2468", "9999") is PinMutationResult.KeystoreError)
        assertEquals("2468", dbB.settingsDao().getSettingsDirect()!!.pinCode)
        assertTrue(dbB.settingsDao().getSettingsDirect()!!.isPinEnabled)

        // Once Keystore recovers, verified change migrates safely and activates new PIN
        cipherB.failEncryptWithTransient = false
        assertTrue(repoB.setOrChangePin("2468", "9999") is PinMutationResult.Success)
        assertEquals("", dbB.settingsDao().getSettingsDirect()!!.pinCode)
        assertTrue(repoB.verifyPin("9999") is PinVerificationResult.Success)

        // Case C: Enabled PIN with neither secure record nor legacy PIN -> reject change, require confirmed recovery
        val dbC = openTempDb("pin_bypass_missing.db")
        seedSyntheticPortfolioData(dbC)
        dbC.settingsDao().saveSettings(
            InitialData.defaultSettings().copy(pinCode = "", isPinEnabled = true)
        )
        val storageC = createIsolatedPrefsStorage("prefs_bypass_missing")
        val cipherC = TestAesGcmCipherProvider()
        val repoC = WealthRepository(dbC, context, pinSecurityManager = PinSecurityManager(storageC, cipherC))

        val changeResC = repoC.setOrChangePin(null, "8888")
        assertTrue(changeResC is PinMutationResult.KeystoreError)
        assertTrue((changeResC as PinMutationResult.KeystoreError).isPermanent)
        assertTrue(repoC.setOrChangePin("1234", "8888") is PinMutationResult.KeystoreError)
        assertTrue(dbC.settingsDao().getSettingsDirect()!!.isPinEnabled)
        assertFalse(repoC.hasSecurePin())

        // Confirmed recovery unlocks cleanly and preserves financial data
        val recRes = repoC.resetPinGateAfterConfirmedRecovery(
            isDeviceAuthenticated = true,
            isUserConfirmed = true
        )
        assertTrue(recRes is ValidationResult.Success)
        assertFalse(dbC.settingsDao().getSettingsDirect()!!.isPinEnabled)
        assertEquals(1, dbC.transactionDao().getAllTransactions().size)
        assertTrue(repoC.setOrChangePin(null, "8888") is PinMutationResult.Success)
        assertTrue(repoC.verifyPin("8888") is PinVerificationResult.Success)
    }

    // FIX 5 TESTS: Reject empty PIN input across Pbkdf2PinHasher, PinSecurityManager, and WealthRepository
    @Test
    fun pinHashingAndValidation_consistentlyRejectEmptyInputAndHashValidPins() = runBlocking {
        val salt = Pbkdf2PinHasher.generateSalt()

        val emptyHashThrown = try {
            Pbkdf2PinHasher.deriveHash("", salt)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("deriveHash must reject empty PIN", emptyHashThrown)

        val blankHashThrown = try {
            Pbkdf2PinHasher.deriveHash("   ", salt)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("deriveHash must reject blank PIN", blankHashThrown)

        assertNull(Pbkdf2PinHasher.validateNewPinFormat(""))
        assertNull(Pbkdf2PinHasher.validateNewPinFormat("   "))

        val validHash = Pbkdf2PinHasher.deriveHash("5921", salt)
        assertEquals(32, validHash.size)
        assertTrue(Pbkdf2PinHasher.verify("5921", salt, validHash))
        assertFalse(Pbkdf2PinHasher.verify("", salt, validHash))
        assertFalse(Pbkdf2PinHasher.verify("   ", salt, validHash))
        assertFalse(Pbkdf2PinHasher.verify("5920", salt, validHash))

        val db = openTempDb("pin_empty_reject.db")
        val storage = createIsolatedPrefsStorage("prefs_empty_reject")
        val cipher = TestAesGcmCipherProvider()
        val manager = PinSecurityManager(storage, cipher)
        val repo = WealthRepository(db, context, pinSecurityManager = manager)

        assertTrue(manager.setPin("") is PinMutationResult.ValidationError)
        assertTrue(manager.setPin("   ") is PinMutationResult.ValidationError)
        assertTrue(repo.setOrChangePin(null, "") is PinMutationResult.ValidationError)
        assertTrue(repo.setOrChangePin(null, "1234") is PinMutationResult.Success)
        assertTrue(manager.verifyPin("") is PinVerificationResult.InvalidPin)
        assertTrue(repo.disablePin("") is PinMutationResult.ValidationError)
        assertTrue(repo.verifyPin("1234") is PinVerificationResult.Success)
    }
}
