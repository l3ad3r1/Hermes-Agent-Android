package com.hermes.agent.work

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Timing math for the simple 5-field cron expressions the app schedules
 * (`minute hour * * dayOfWeek`). WorkManager only supports fixed periods, so
 * honoring "daily at 8 am" takes three pieces:
 *
 *  1. [periodMinutes]      — the repeat interval (hourly / daily / weekly)
 *  2. [initialDelayMillis] — delay so the FIRST run lands on the cron time
 *  3. [shouldRunNow]       — runtime day-of-week gate, because a 24h period
 *                            can't express "weekdays only" (audit M2)
 *
 * Supported field forms: minute = int or `*`; hour = int or `*`;
 * day-of-week = `*`, single int, range `a-b`, or comma list (0 or 7 = Sunday).
 * Day-of-month and month fields are ignored. Unparseable expressions fall
 * back to the pre-M2 behavior: 24h period, no anchor, always run.
 */
object CronTiming {

    data class Spec(
        val minute: Int?,          // null = every minute (treated as :00 for anchoring)
        val hour: Int?,            // null = every hour
        val daysOfWeek: Set<Int>?, // ISO 1=Mon..7=Sun; null = every day
    )

    fun parse(expr: String): Spec? {
        val fields = expr.trim().split(Regex("\\s+"))
        if (fields.size != 5) return null

        val minute = fields[0].let { if (it == "*") null else it.toIntOrNull()?.takeIf { m -> m in 0..59 } }
        if (minute == null && fields[0] != "*") return null

        val hour = fields[1].let { if (it == "*") null else it.toIntOrNull()?.takeIf { h -> h in 0..23 } }
        if (hour == null && fields[1] != "*") return null

        val dow: Set<Int>? = when {
            fields[4] == "*" -> null
            else -> parseDowField(fields[4]) ?: return null
        }
        return Spec(minute, hour, dow)
    }

    /** Cron day-of-week (0/7=Sun, 1=Mon..6=Sat) → ISO (1=Mon..7=Sun). */
    private fun parseDowField(field: String): Set<Int>? {
        fun cronToIso(d: Int): Int? = when (d) {
            0, 7 -> 7
            in 1..6 -> d
            else -> null
        }
        val out = mutableSetOf<Int>()
        for (part in field.split(",")) {
            val range = part.split("-")
            when (range.size) {
                1 -> out += cronToIso(range[0].toIntOrNull() ?: return null) ?: return null
                2 -> {
                    val a = range[0].toIntOrNull() ?: return null
                    val b = range[1].toIntOrNull() ?: return null
                    if (a > b || a !in 0..7 || b !in 0..7) return null
                    for (d in a..b) out += cronToIso(d) ?: return null
                }
                else -> return null
            }
        }
        return out.ifEmpty { null }
    }

    /** Repeat interval: hourly when the hour is `*`, weekly when the job
     *  fires on exactly one weekday, daily otherwise. */
    fun periodMinutes(expr: String): Long {
        val spec = parse(expr) ?: return 24 * 60L
        return when {
            spec.hour == null -> 60L
            spec.daysOfWeek?.size == 1 -> 7 * 24 * 60L
            else -> 24 * 60L
        }
    }

    /** Millis from [nowMillis] until the next time the expression fires. */
    fun initialDelayMillis(
        expr: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val spec = parse(expr) ?: return 0L
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        val minute = spec.minute ?: 0

        var candidate = now.withMinute(minute).withSecond(0).withNano(0)
        if (spec.hour != null) candidate = candidate.withHour(spec.hour)

        val step: Duration = if (spec.hour == null) Duration.ofHours(1) else Duration.ofDays(1)
        var guard = 0
        while (candidate <= now || !dowMatches(spec, candidate)) {
            candidate = candidate.plus(step)
            if (++guard > 24 * 8) return 0L // safety net — give up on anchoring
        }
        return Duration.between(now, candidate).toMillis()
    }

    /** Runtime gate: does the expression allow firing at this moment's weekday? */
    fun shouldRunNow(
        expr: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val spec = parse(expr) ?: return true
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        return dowMatches(spec, now)
    }

    private fun dowMatches(spec: Spec, t: ZonedDateTime): Boolean =
        spec.daysOfWeek == null || t.dayOfWeek.value in spec.daysOfWeek
}
