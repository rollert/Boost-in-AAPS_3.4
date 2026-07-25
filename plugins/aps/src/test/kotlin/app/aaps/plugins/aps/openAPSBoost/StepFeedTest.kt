package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * F1 (2026-07-07) — step-source availability guard.
 *
 * These exercise the exact predicates calculateBoostActivity now evaluates (extracted to StepFeed
 * so they're unit-testable): a NONE feed (phone never reported this boot, no fresh wear row) must
 * suppress the INACTIVE profile drop and the steps-based sleep-in backstop — 0 steps from a dead
 * feed is "unknown", not "sedentary" — while a LIVE feed with genuinely zero steps behaves exactly
 * as before.
 */
class StepFeedTest {

    private val FRESH = WearStepSource.FRESH_MS

    // ── Feed labels + availability (RT boostSteps_feed) ─────────────────────────────────────────

    @Test fun `both feeds live - phone+wear`() {
        val s = StepFeed.State(phoneLive = true, wearAgeMs = 3 * 60_000L)
        assertThat(s.available).isTrue()
        assertThat(s.label).isEqualTo("phone+wear")
    }

    @Test fun `phone only`() {
        val s = StepFeed.State(phoneLive = true, wearAgeMs = null)
        assertThat(s.available).isTrue()
        assertThat(s.label).isEqualTo("phone")
        // stale wear doesn't count towards the label either
        assertThat(StepFeed.State(true, FRESH + 60_000L).label).isEqualTo("phone")
    }

    @Test fun `wear only`() {
        val s = StepFeed.State(phoneLive = false, wearAgeMs = FRESH)   // exactly at the window edge = fresh
        assertThat(s.available).isTrue()
        assertThat(s.label).isEqualTo("wear")
    }

    @Test fun `no feed - none`() {
        val s = StepFeed.State(phoneLive = false, wearAgeMs = null)
        assertThat(s.available).isFalse()
        assertThat(s.label).isEqualTo("none")
        val stale = StepFeed.State(phoneLive = false, wearAgeMs = FRESH + 1)
        assertThat(stale.available).isFalse()
        assertThat(stale.label).isEqualTo("none")
    }

    @Test fun `unavailable breadcrumb names the failure per feed`() {
        assertThat(StepFeed.State(false, null).unavailableNote())
            .isEqualTo("steps:UNAVAILABLE(phone=none-this-boot, wear=none)")
        assertThat(StepFeed.State(false, 47 * 60_000L).unavailableNote())
            .isEqualTo("steps:UNAVAILABLE(phone=none-this-boot, wear=stale 47m)")
    }

    // ── INACTIVE branch guard ────────────────────────────────────────────────────────────────────

    @Test fun `NONE feed - INACTIVE never fires, even at zero steps`() {
        assertThat(StepFeed.inactivityEligible(stepsAvailable = false, currentProfileSwitch = 100, recentSteps60Min = 0, inactivitySteps = 200)).isFalse()
    }

    @Test fun `LIVE feed with zero steps - INACTIVE fires as today (real sedentary unchanged)`() {
        assertThat(StepFeed.inactivityEligible(stepsAvailable = true, currentProfileSwitch = 100, recentSteps60Min = 0, inactivitySteps = 200)).isTrue()
    }

    @Test fun `LIVE feed with steps above threshold or non-100 profile - not eligible`() {
        assertThat(StepFeed.inactivityEligible(true, 100, 250, 200)).isFalse()
        assertThat(StepFeed.inactivityEligible(true, 80, 0, 200)).isFalse()
    }

    // ── Sleep-in backstop guard ──────────────────────────────────────────────────────────────────

    private val nightEnd = 1_000_000_000L
    private val sleepInMs = 2 * 3_600_000L

    @Test fun `NONE feed - sleep-in gate never engages`() {
        assertThat(StepFeed.sleepInActive(stepsAvailable = false, nowMs = nightEnd + 60_000L, nightEndMs = nightEnd, sleepInMs = sleepInMs, recentSteps60Min = 0, sleepInSteps = 75)).isFalse()
    }

    @Test fun `LIVE feed - sleep-in fires inside the window below threshold, as today`() {
        assertThat(StepFeed.sleepInActive(true, nightEnd + 60_000L, nightEnd, sleepInMs, 10, 75)).isTrue()
        // steps at/above threshold → awake
        assertThat(StepFeed.sleepInActive(true, nightEnd + 60_000L, nightEnd, sleepInMs, 75, 75)).isFalse()
        // outside the window (before night end / after window close) → no gate
        assertThat(StepFeed.sleepInActive(true, nightEnd - 1, nightEnd, sleepInMs, 10, 75)).isFalse()
        assertThat(StepFeed.sleepInActive(true, nightEnd + sleepInMs, nightEnd, sleepInMs, 10, 75)).isFalse()
    }

    // ── Lie-in FAILSAFE decision (false-AWAKE gap) ───────────────────────────────────────────────

    @Test fun `sleep-in window inactive - failsafe never engages regardless of detector`() {
        assertThat(StepFeed.lieInFailsafeEngages(sleepInActive = false, autoBySleepActive = false, detectorSleeping = false)).isFalse()
        assertThat(StepFeed.lieInFailsafeEngages(sleepInActive = false, autoBySleepActive = true, detectorSleeping = true)).isFalse()
    }

    @Test fun `auto-by-sleep OFF - failsafe engages on low steps (clock-only night mode, unchanged)`() {
        assertThat(StepFeed.lieInFailsafeEngages(sleepInActive = true, autoBySleepActive = false, detectorSleeping = false)).isTrue()
        // detector state is irrelevant when auto-by-sleep is off
        assertThat(StepFeed.lieInFailsafeEngages(sleepInActive = true, autoBySleepActive = false, detectorSleeping = true)).isTrue()
    }

    @Test fun `auto-by-sleep ON and detector SLEEPING - failsafe stands down (detector drives)`() {
        assertThat(StepFeed.lieInFailsafeEngages(sleepInActive = true, autoBySleepActive = true, detectorSleeping = true)).isFalse()
    }

    @Test fun `auto-by-sleep ON but detector AWAKE in the lie-in window - failsafe ENGAGES (false-AWAKE gap closed)`() {
        // The regression the fix targets: a dawn false-AWAKE with the user still in bed (low 60m steps)
        // previously left BOTH protections off. Steps are ground truth → the failsafe must re-engage.
        assertThat(StepFeed.lieInFailsafeEngages(sleepInActive = true, autoBySleepActive = true, detectorSleeping = false)).isTrue()
    }
}
