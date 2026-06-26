package app.aaps.plugins.aps.openAPSBoost

import app.aaps.core.data.model.SC
import kotlin.math.max

/**
 * WearStepSource — Boost activity-load (2026-06-24). Bridges AAPS Wear step data into Boost.
 *
 * AAPS Wear's StepCountListener sends `ActionStepsRate` (steps over rolling 5/15/30/60/180-min
 * windows) to the phone, persisted to the `stepsCount` (SC) table. Worn on the wrist, this is a
 * more faithful step source than the phone pedometer (which only counts when carried) and entirely
 * independent of Garmin Connect / Health Connect. These pure helpers turn the SC rows into the two
 * things Boost's activity-load shadow needs: a freshness check and a cumulative-today total.
 *
 * SHADOW: the activity-load that consumes this is logged-only; nothing here touches dosing.
 */
object WearStepSource {

    private const val FIVE_MIN_MS = 300_000L
    /** A wear feed is "live" if it produced a row within this window. ~2–3 sampling intervals. */
    const val FRESH_MS = 12 * 60_000L

    fun latest(scList: List<SC>): SC? = scList.maxByOrNull { it.timestamp }

    /** True when the watch has reported steps recently — i.e. it's worn and sampling. */
    fun isFresh(scList: List<SC>, nowMs: Long): Boolean =
        latest(scList)?.let { nowMs - it.timestamp <= FRESH_MS } ?: false

    /**
     * Cumulative steps since [dayStartMs] reconstructed from the rolling 5-min windows: take one
     * `steps5min` value per non-overlapping 5-min slot (the max seen in that slot, since the watch
     * samples more often than every 5 min and windows overlap) and sum. Approximate but stable, and
     * free of the double-counting that summing overlapping windows would cause.
     */
    fun stepsToday(scList: List<SC>, dayStartMs: Long, nowMs: Long): Int {
        val perSlot = HashMap<Long, Int>()
        for (sc in scList) {
            if (sc.timestamp < dayStartMs || sc.timestamp > nowMs) continue
            val slot = sc.timestamp / FIVE_MIN_MS
            perSlot[slot] = max(perSlot.getOrDefault(slot, 0), sc.steps5min)
        }
        return perSlot.values.sum()
    }
}
