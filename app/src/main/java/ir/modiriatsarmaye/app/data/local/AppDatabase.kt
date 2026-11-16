package ir.modiriatsarmaye.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ir.modiriatsarmaye.app.data.model.*
import kotlinx.coroutines.CoroutineScope

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN commissionType TEXT NOT NULL DEFAULT 'FIXED'")
        db.execSQL("ALTER TABLE transactions ADD COLUMN commissionRate REAL NOT NULL DEFAULT 0.0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE current_prices ADD COLUMN unit TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE current_prices ADD COLUMN priceType TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE current_prices ADD COLUMN isAutoUpdated INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE current_prices ADD COLUMN status TEXT NOT NULL DEFAULT 'FRESH'")
        db.execSQL("ALTER TABLE current_prices ADD COLUMN errorMessage TEXT")

        db.execSQL("ALTER TABLE app_settings ADD COLUMN priceUpdateFrequency TEXT NOT NULL DEFAULT 'ON_CONNECTIVITY'")
        db.execSQL("ALTER TABLE app_settings ADD COLUMN lastPriceUpdateTimestamp INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE app_settings ADD COLUMN googleAccountEmail TEXT")
        db.execSQL("ALTER TABLE app_settings ADD COLUMN googleAccountName TEXT")
        db.execSQL("ALTER TABLE app_settings ADD COLUMN lastGoogleDriveBackupTimestamp INTEGER")
        db.execSQL("ALTER TABLE app_settings ADD COLUMN lastGoogleDriveBackupSummary TEXT")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE current_prices ADD COLUMN instrumentId TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_transactions_timestamp` ON `transactions` (`timestamp`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_current_prices_instrumentId_lastUpdated` ON `current_prices` (`instrumentId`, `lastUpdated`)"
        )
    }
}

@Database(
    entities = [
        TransactionEntity::class,
        CurrentPriceEntity::class,
        GoalEntity::class,
        LiabilityEntity::class,
        DividendEntity::class,
        AppSettingsEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun currentPriceDao(): CurrentPriceDao
    abstract fun goalDao(): GoalDao
    abstract fun liabilityDao(): LiabilityDao
    abstract fun dividendDao(): DividendDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        const val DATABASE_NAME = "modiriat_sarmaye_db"

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5
        )

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun createCallback(): RoomDatabase.Callback = DatabaseCallback()

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .addCallback(createCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun getDatabase(context: Context, scope: CoroutineScope): AppDatabase {
            return getInstance(context)
        }

        private class DatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                InitialData.seedDefaultSettingsIfMissing(db)
            }
        }
    }
}
