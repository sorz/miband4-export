package cn.edu.sustech.cse.miband.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.threeten.bp.LocalDateTime

@Dao
interface RecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<Record>)

    @Query("SELECT time FROM record ORDER BY time DESC LIMIT 1")
    suspend fun loadLastTime(): LocalDateTime?

    @Query("DELETE FROM record")
    suspend fun deleteAll()

    @Query("SELECT * FROM record")
    suspend fun selectAll(): List<Record>
}