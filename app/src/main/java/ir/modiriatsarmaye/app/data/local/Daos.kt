package ir.modiriatsarmaye.app.data.local

import androidx.room.*
import ir.modiriatsarmaye.app.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    fun getAllTransactionsFlow(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    suspend fun getAllTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<TransactionEntity>)

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Delete
    suspend fun deleteTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun clearAllTransactions()

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun getTransactionCount(): Int
}

@Dao
interface CurrentPriceDao {
    @Query("SELECT * FROM current_prices")
    fun getAllPricesFlow(): Flow<List<CurrentPriceEntity>>

    @Query("SELECT * FROM current_prices")
    suspend fun getAllPrices(): List<CurrentPriceEntity>

    @Query("SELECT * FROM current_prices WHERE assetSymbolOrName = :symbolOrName")
    suspend fun getPrice(symbolOrName: String): CurrentPriceEntity?

    @Query("SELECT * FROM current_prices WHERE instrumentId = :instrumentId")
    suspend fun getPricesByInstrumentId(instrumentId: String): List<CurrentPriceEntity>

    @Query("SELECT * FROM current_prices WHERE instrumentId = :instrumentId ORDER BY lastUpdated DESC LIMIT 1")
    suspend fun getPriceByInstrumentId(instrumentId: String): CurrentPriceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrice(price: CurrentPriceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrices(prices: List<CurrentPriceEntity>)

    @Delete
    suspend fun deletePrice(price: CurrentPriceEntity)

    @Query("DELETE FROM current_prices")
    suspend fun clearAllPrices()
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM financial_goals ORDER BY id ASC")
    fun getAllGoalsFlow(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM financial_goals ORDER BY id ASC")
    suspend fun getAllGoals(): List<GoalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(goal: GoalEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoals(goals: List<GoalEntity>)

    @Update
    suspend fun updateGoal(goal: GoalEntity)

    @Delete
    suspend fun deleteGoal(goal: GoalEntity)

    @Query("DELETE FROM financial_goals WHERE id = :id")
    suspend fun deleteGoalById(id: Long)

    @Query("DELETE FROM financial_goals")
    suspend fun clearAllGoals()
}

@Dao
interface LiabilityDao {
    @Query("SELECT * FROM liabilities ORDER BY id ASC")
    fun getAllLiabilitiesFlow(): Flow<List<LiabilityEntity>>

    @Query("SELECT * FROM liabilities ORDER BY id ASC")
    suspend fun getAllLiabilities(): List<LiabilityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLiability(liability: LiabilityEntity): Long

    @Update
    suspend fun updateLiability(liability: LiabilityEntity)

    @Delete
    suspend fun deleteLiability(liability: LiabilityEntity)

    @Query("DELETE FROM liabilities WHERE id = :id")
    suspend fun deleteLiabilityById(id: Long)

    @Query("DELETE FROM liabilities")
    suspend fun clearAllLiabilities()
}

@Dao
interface DividendDao {
    @Query("SELECT * FROM dividends ORDER BY id DESC")
    fun getAllDividendsFlow(): Flow<List<DividendEntity>>

    @Query("SELECT * FROM dividends ORDER BY id DESC")
    suspend fun getAllDividends(): List<DividendEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDividend(dividend: DividendEntity): Long

    @Update
    suspend fun updateDividend(dividend: DividendEntity)

    @Delete
    suspend fun deleteDividend(dividend: DividendEntity)

    @Query("DELETE FROM dividends WHERE id = :id")
    suspend fun deleteDividendById(id: Long)

    @Query("DELETE FROM dividends")
    suspend fun clearAllDividends()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getSettingsFlow(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): AppSettingsEntity?

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettingsDirect(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: AppSettingsEntity)
}
