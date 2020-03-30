package cn.edu.sustech.cse.miband

import android.app.Application
import android.content.Context
import androidx.room.Room
import cn.edu.sustech.cse.miband.db.AppDatabase
import com.jakewharton.threetenabp.AndroidThreeTen

class MyApplication : Application() {
    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "records.db").build()
    }

    override fun onCreate() {
        super.onCreate()
        AndroidThreeTen.init(this)
    }
}

val Context.database
    get() = (applicationContext as MyApplication).database
