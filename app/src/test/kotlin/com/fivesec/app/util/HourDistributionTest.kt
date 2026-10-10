package com.fivesec.app.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 24 小时分桶纯函数口径（specs/011）：显式时区、边界时刻、空表全零。 */
class HourDistributionTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun millis(date: String, time: String): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant().toEpochMilli()

    @Test
    fun `各小时事件落入对应桶`() {
        val buckets = HourDistribution.of(
            listOf(
                millis("2026-10-10", "00:05"),
                millis("2026-10-10", "22:00"),
                millis("2026-10-10", "22:30"),
                millis("2026-10-10", "23:15"),
            ),
            zone,
        )
        assertEquals(24, buckets.size)
        assertEquals(1, buckets[0])
        assertEquals(2, buckets[22])
        assertEquals(1, buckets[23])
        assertEquals(0, buckets[12])
        assertEquals(4, buckets.sum())
    }

    @Test
    fun `小时边界时刻归入本小时`() {
        // 22:00:00.000 属于 22 桶；22:59:59.999 仍在 22 桶；23:00:00.000 起 23 桶；00:00:00.000 属 0 桶
        val buckets = HourDistribution.of(
            listOf(
                millis("2026-10-10", "22:00"),
                ZonedDateTime.of(LocalDate.parse("2026-10-10"), LocalTime.parse("22:59:59.999"), zone).toInstant().toEpochMilli(),
                millis("2026-10-10", "23:00"),
                millis("2026-10-10", "00:00"),
            ),
            zone,
        )
        assertEquals(2, buckets[22])
        assertEquals(1, buckets[23])
        assertEquals(1, buckets[0])
    }

    @Test
    fun `空列表返回全零且桶数恒定`() {
        val buckets = HourDistribution.of(emptyList(), zone)
        assertEquals(HourDistribution.BUCKETS, buckets.size)
        assertEquals(0, buckets.sum())
    }

    @Test
    fun `同一时刻在不同时区落入不同桶`() {
        val ts = millis("2026-10-10", "08:00") // 上海 08:00 = UTC 00:00
        val shanghai = HourDistribution.of(listOf(ts), ZoneId.of("Asia/Shanghai"))
        val utc = HourDistribution.of(listOf(ts), ZoneId.of("UTC"))
        assertEquals(1, shanghai[8])
        assertEquals(0, shanghai[0])
        assertEquals(1, utc[0])
        assertEquals(0, utc[8])
    }

    @Test
    fun `跨日事件各自按本地小时计入`() {
        val buckets = HourDistribution.of(
            listOf(
                millis("2026-10-09", "23:59"),
                millis("2026-10-10", "00:01"),
            ),
            zone,
        )
        assertEquals(1, buckets[23])
        assertEquals(1, buckets[0])
        assertArrayEquals(IntArray(24) { if (it == 0 || it == 23) 1 else 0 }, buckets)
    }
}
