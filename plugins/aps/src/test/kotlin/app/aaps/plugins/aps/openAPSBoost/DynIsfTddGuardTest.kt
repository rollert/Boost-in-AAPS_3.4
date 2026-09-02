package app.aaps.plugins.aps.openAPSBoost

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * 2026-07-30 implausible-TDD guard for dynamic ISF.
 *
 * Field case: a cross-fork migration onto a fresh AAPS database reported TDD 3.1-4.1 U/day against a
 * true ~20. Dynamic ISF (1800/(tdd x logTerm)) reached 5550-8944 mg/dL/U against a profile ISF of 100,
 * insulinReq computed at or below zero, and the loop delivered nothing for 3.5 h while BG climbed to
 * 276 — 19 consecutive zero temp basals, no lows, no alarm. The pre-existing `tdd > 0` check passed
 * throughout, so the guard is anchored on the TDD the profile itself implies (1800 rule) instead.
 *
 * 2026-09-02 7D-ANCHORED GUARD (variant C): the guard fires on ① tdd7D < 0.35 × implied (history as
 * a whole suspect — corruption destroys every window at once) OR ② blended tdd < 0.15 × implied
 * (near-zero delivery window). A healthy 7D with a small blend is real physiology and is TRUSTED —
 * the original blend-anchored check discarded it (7D=40, W8H=8 → blend 11.2 < 14 → profile ISF).
 */
class DynIsfTddGuardTest {

    @Test fun `the field case is caught - profile ISF 100 implies TDD 18, 7D and blend both corrupted`() {
        // Fresh DB → every window is wrong, including 7D: leg ① fires.
        assertThat(tddImplausibleForProfile(tdd7D = 4.0, tdd = 4.0, profileSens = 100.0)).isTrue()
        assertThat(tddImplausibleForProfile(tdd7D = 3.1, tdd = 3.1, profileSens = 100.0)).isTrue()
    }

    @Test fun `a healthy TDD for the same profile passes`() {
        // implied 18.0, corruption floor 6.3, near-zero floor 2.7 — true TDD was ~20 in the field.
        assertThat(tddImplausibleForProfile(tdd7D = 20.2, tdd = 20.2, profileSens = 100.0)).isFalse()
        assertThat(tddImplausibleForProfile(tdd7D = 20.2, tdd = 6.4, profileSens = 100.0)).isFalse()
    }

    @Test fun `a healthy 7D with a collapsed blend is TRUSTED - the fast-sensitivity case`() {
        // 7D=40 with profile ISF 45 (implied 40): W8H=8 blends to 11.2 = 28% of implied.
        // The old blend-anchored guard fired here (11.2 < 14) — exactly the regression this fixes.
        assertThat(tddImplausibleForProfile(tdd7D = 40.0, tdd = 11.2, profileSens = 45.0)).isFalse()
        // Same story deeper in the range: W8H=6 blends to 9.4 (23.5% of implied) — still trusted.
        assertThat(tddImplausibleForProfile(tdd7D = 40.0, tdd = 9.4, profileSens = 45.0)).isFalse()
    }

    @Test fun `a degenerate blend with a healthy 7D falls back - pump-offline case`() {
        // 7D healthy, but the recent window collapsed to near-noise → leg ② fires.
        assertThat(tddImplausibleForProfile(tdd7D = 40.0, tdd = 5.0, profileSens = 45.0)).isTrue()
        // Boundary: near-zero floor is 0.15 × 40 = 6.0.
        assertThat(tddImplausibleForProfile(tdd7D = 40.0, tdd = 6.0, profileSens = 45.0)).isFalse()
        assertThat(tddImplausibleForProfile(tdd7D = 40.0, tdd = 5.99, profileSens = 45.0)).isTrue()
    }

    @Test fun `the floor is self-scaling - a U200 or low-TDD user is judged by their OWN profile`() {
        // Aggressive profile ISF 30 => implied 60 => 7D floor 21, near-zero floor 9.
        assertThat(tddImplausibleForProfile(tdd7D = 25.0, tdd = 25.0, profileSens = 30.0)).isFalse()
        assertThat(tddImplausibleForProfile(tdd7D = 15.0, tdd = 15.0, profileSens = 30.0)).isTrue()
        // Insensitive profile ISF 300 => implied 6 => 7D floor 2.1, near-zero floor 0.9.
        // A genuinely small TDD is NOT flagged.
        assertThat(tddImplausibleForProfile(tdd7D = 4.0, tdd = 4.0, profileSens = 300.0)).isFalse()
        // ...which is the case the old `tdd > 0` check could not distinguish from the field failure:
        // the SAME tdd of 4.0 is implausible on a 100 profile and perfectly normal on a 300 one.
    }

    @Test fun `fails OPEN with no usable profile reference`() {
        assertThat(tddImplausibleForProfile(tdd7D = 0.1, tdd = 0.1, profileSens = 0.0)).isFalse()
        assertThat(tddImplausibleForProfile(tdd7D = 0.1, tdd = 0.1, profileSens = -5.0)).isFalse()
    }

    @Test fun `boundaries are exactly the implied fractions`() {
        // profileSens 100 => implied 18.0 => 7D floor 6.3, near-zero floor 2.7.
        assertThat(tddImplausibleForProfile(tdd7D = 6.3, tdd = 6.3, profileSens = 100.0)).isFalse()  // not below
        assertThat(tddImplausibleForProfile(tdd7D = 6.29, tdd = 6.29, profileSens = 100.0)).isTrue() // ① fires
        // Leg ② alone: healthy 7D, blend just below 2.7.
        assertThat(tddImplausibleForProfile(tdd7D = 20.0, tdd = 2.69, profileSens = 100.0)).isTrue()
    }
}
