package cn.edu.sustech.cse.miband.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import org.threeten.bp.LocalDateTime

@Dao
interface RecordDao {
    @Insert
    fun insertAll(records: List<Record>)

    @Query("SELECT time FROM record ORDER BY time DESC LIMIT 1")
    fun loadLastTime(): LocalDateTime?

}