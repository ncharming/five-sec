package com.fivesec.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 一个被选为拦截对象的已安装应用。 */
@Entity(tableName = "target_apps")
data class TargetApp(
    @PrimaryKey val packageName: String,
    val appName: String,           // 应用友好名称（如"小红书"）
    val isEnabled: Boolean = true,
    val isDefault: Boolean = false,
    val addedAt: Long,
)
