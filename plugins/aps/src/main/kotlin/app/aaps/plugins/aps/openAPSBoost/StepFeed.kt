package app.aaps.plugins.aps.openAPSBoost

/**
 * StepFeed — step-source availability guard (F1, 2026-07-07).
 *
 * Boost's INACTIVE detection and the steps-based sleep-in backstop both read the phone pedometer's
 * rolling windows and treat 0 as "the user is not moving". But 0 is ALSO what a dead feed reports:
 * the hardware TYPE_STEP_COUNTER delivers nothing until its first event after boot (StepService's
 * previousStepCount stays -1 and every window reads 0), so a phone that rebooted overnight — or a
 * user whose only step source is a watch that died — was classified INACTIVE (profile lowered to
 * inactivityPct, i.e. MORE aggressive dosing) and could trip the sleep-in gate on pure absence of
 * data.
 *
 * This model makes "no data" explicit: the step feed is AVAILABLE when the phone pedometer has
 * reported at least once this boot ([StepService.feedState] == LIVE) OR the AAPS Wear watch has
 * written an SC row within [WearStepSource.FRESH_MS]. When unavailable, the caller
 * (calculateBoostActivity) must:
 *  (a) skip the INACTIVE branch (stay at 100%, activityState = "steps-unknown"),
 *  (b) skip the steps-based sleep-in backstop (the night window + HR sleep detection still
 *      protect), and
 *  (c) breadcrumb the outage into the debug reason + the `boostSteps_feed` RT field.
 * `isActive` needs no guard — false is the correct answer when steps are unknown (activity must be
 * positively evidenced).
 *
 * Pure / no I/O — unit-testable without the plugin.
 */
internal object StepFeed {

    /**
     * Snapshot of the two live-capable step feeds this cycle.
     * @param phoneLive  phone pedometer has reported this boot ([StepService.FeedState.LIVE]).
     * @param wearAgeMs  age of the newest wear SC row, or null when the SC table has none in the
     *                   queried window.
     */
    data class State(val phoneLive: Boolean, val wearAgeMs: Long?) {

        val wearFresh: Boolean get() = wearAgeMs != null && wearAgeMs <= WearStepSource.FRESH_MS

        /** At least one source is actually feeding — 0-step windows are trustworthy. */
        val available: Boolean get() = phoneLive || wearFresh

        /** RT `boostSteps_feed` value: "phone+wear" | "phone" | "wear" | "none". */
        val label: String
            get() = when {
                phoneLive && wearFresh -> "phone+wear"
                phoneLive              -> "phone"
                wearFresh              -> "wear"
                else                   -> "none"
            }

        /** Debug-reason breadcrumb for the unavailable case. */
        fun unavailableNote(): String {
            val phone = if (phoneLive) "live" else "none-this-boot"
            val wear = wearAgeMs?.let { "stale ${it / 60_000L}m" } ?: "none"
            return "steps:UNAVAILABLE(phone=$phone, wear=$wear)"
        }
    }

    /**
     * The INACTIVE branch may only fire when the feed is available — this is the exact predicate
     * calculateBoostActivity evaluates (extracted so the guard is unit-testable).
     */
    fun inactivityEligible(stepsAvailable: Boolean, currentProfileSwitch: Int, recentSteps60Min: Int, inactivitySteps: Int): Boolean =
        stepsAvailable && currentProfileSwitch == 100 && recentSteps60Min < inactivitySteps

    /**
     * Steps-based sleep-in (lie-in) backstop — as above, gated on feed availability so absence of
     * step data can never read as "still in bed". Window: [nightEndMs, nightEndMs + sleepInMs).
     */
    fun sleepInActive(stepsAvailable: Boolean, nowMs: Long, nightEndMs: Long, sleepInMs: Long, recentSteps60Min: Int, sleepInSteps: Int): Boolean =
        stepsAvailable && nowMs in nightEndMs until (nightEndMs + sleepInMs) && recentSteps60Min < sleepInSteps

    /**
     * The steps-based lie-in FAILSAFE decision (extracted so the false-AWAKE gap is unit-testable).
     *
     * Boost is disabled during a morning lie-in when [sleepInActive] AND the sleep detector is NOT
     * currently holding sleep for us. The detector holds sleep only when auto-by-sleep is ON and its
     * state is SLEEPING — then its night-mode already suppresses, so the failsafe stands down. But if
     * auto-by-sleep is OFF, OR the detector has (falsely) WOKEN during the lie-in window, the failsafe
     * must engage: near-zero 60-min steps are the ground truth that the user is still in bed, and we
     * must not amplify a dawn rise into a full meal-SMB on a false-AWAKE. Prior shadow-branch code gated
     * this on `!autoBySleepActive` alone, which stood the failsafe down for the ENTIRE lie-in whenever
     * auto-by-sleep was on — leaving both protections off exactly in the detector's false-AWAKE mode.
     */
    fun lieInFailsafeEngages(sleepInActive: Boolean, autoBySleepActive: Boolean, detectorSleeping: Boolean): Boolean =
        sleepInActive && !(autoBySleepActive && detectorSleeping)
}
