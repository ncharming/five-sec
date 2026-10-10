package com.fivesec.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次「放行的使用」会话（specs/012 使用时长守护）：拦截页选「打开」开始，结束/闲置/被取代/
 * 服务销毁时结束——**结束时单行 INSERT**（append-only，无 UPDATE：开始行若需回填结束时刻会破坏
 * 只增契约；进程被杀的会话整行丢失，方向是低估时长，可接受）。时长为墙钟口径（含中途短暂离开），
 * 命名「会话/停留」而非「净使用」；「净使用」需前台 tick，成本高收益低，不做。
 */
@Entity(tableName = "usage_sessions")
data class UsageSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startedAt: Long,           // 选「打开」时刻
    val endedAt: Long,             // 结束时刻（语义随 endReason，见枚举）
    val durationMillis: Long,      // endedAt - startedAt，≥0
    val guardShownCount: Int,      // 本场回弹层实际弹出次数（0..3）
    val endReason: UsageSessionEndReason,
)

/** 会话结束原因（TEXT 存储，TypeConverter 双向映射）。 */
enum class UsageSessionEndReason {
    /** 回弹层选「结束」——用户主动收尾，endedAt=now。 */
    GUARD_ENDED,

    /** 新会话开启（重开同应用或换目标），endedAt=旧会话最后停留时刻。 */
    SUPERSEDED,

    /** 离开 ≥30 分钟（惰性判定：下次前台切换事件检查），endedAt=最后停留时刻。 */
    IDLE_TIMEOUT,

    /** 服务 onUnbind/onDestroy 兜底（尽力而为），endedAt=最后停留时刻。 */
    SERVICE_STOPPED,
}
