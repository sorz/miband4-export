package cn.edu.sustech.cse.miband.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.threeten.bp.LocalDateTime

@Entity
data class Record (
    @PrimaryKey val time: LocalDateTime,
    @ColumnInfo val step: Int,
    @ColumnInfo val heartRate: Int
)
