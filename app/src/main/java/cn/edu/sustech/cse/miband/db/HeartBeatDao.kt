package cn.edu.sustech.cse.miband.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HeartBeatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(records: HeartBeat)

    @Query("DELETE FROM heartbeat")
    suspend fun deleteAll()

    @Query("SELECT * FROM heartbeat")
    suspend fun selectAll(): List<HeartBeat>
}
