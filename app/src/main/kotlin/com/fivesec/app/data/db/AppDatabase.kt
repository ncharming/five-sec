package com.fivesec.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.domain.model.TargetApp

@Database(
    entities = [TargetApp::class, InterceptionEvent::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(InterceptionOutcomeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targetAppDao(): TargetAppDao
    abstract fun interceptionEventDao(): InterceptionEventDao
}

class InterceptionOutcomeConverter {
    @TypeConverter
    fun toName(outcome: InterceptionOutcome): String = outcome.name

    @TypeConverter
    fun fromName(name: String): InterceptionOutcome = InterceptionOutcome.valueOf(name)
}
