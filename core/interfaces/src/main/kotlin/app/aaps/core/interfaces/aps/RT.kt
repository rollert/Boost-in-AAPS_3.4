package app.aaps.core.interfaces.aps

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
data class RT(
    var algorithm: APSResult.Algorithm = APSResult.Algorithm.UNKNOWN,
    var runningDynamicIsf: Boolean,
    @Serializable(with = TimestampToIsoSerializer::class)
    var timestamp: Long? = null,
    val temp: String = "absolute",
    var bg: Double? = null,
    var tick: String? = null,
    var eventualBG: Double? = null,
    var targetBG: Double? = null,
    var snoozeBG: Double? = null, // AMA only
    var insulinReq: Double? = null,
    var carbsReq: Int? = null,
    var carbsReqWithin: Int? = null,
    var units: Double? = null, // micro bolus
    @Serializable(with = TimestampToIsoSerializer::class)
    var deliverAt: Long? = null, // The time at which the micro bolus should be delivered
    var sensitivityRatio: Double? = null, // autosens ratio (fraction of normal basal)
    @Serializable(with = StringBuilderSerializer::class)
    var reason: StringBuilder = StringBuilder(),
    var duration: Int? = null,
    var rate: Double? = null,
    var predBGs: Predictions? = null,
    var COB: Double? = null,
    var IOB: Double? = null,
    var variable_sens: Double? = null,
    var isfMgdlForCarbs: Double? = null, // used to pass to AAPS client


    var consoleLog: MutableList<String>? = null,
    var consoleError: MutableList<String>? = null,

    // Boost-specific: tier dosing decision (uploaded to Nightscout)
    var boostTier: String? = null,               // Which tier was triggered (e.g. "UAM_BOOST", "PERCENT_SCALE", etc.)
    var boostActive: Boolean? = null,            // Whether Boost was in its active time window
    var fastCarbProtection: Boolean? = null,     // Whether fast-carb rebound protection suppressed UAM/Accel tiers this cycle

    // Boost-specific: DynamicISF data (uploaded to Nightscout)
    var dynamicISF: Double? = null,              // Dosing ISF (future_sens) used for insulin requirement
    var predictionISF: Double? = null,           // Prediction ISF (variable_sens) used for BG predictions
    var sensNormalTarget: Double? = null,        // ISF at normal target BG level
    var tdd: Double? = null,                     // Blended TDD value used in ISF calculation
    var tddRatio: Double? = null,                // Sensitivity ratio derived from TDD (8h weighted / 7D)
    var insulinReqPctEffective: Double? = null,  // Effective insulin required % used for dosing
    var deltaAcceleration: Double? = null,       // Delta acceleration percentage
    var boostProfileSwitch: Int? = null,         // Effective profile % (activity-adjusted)

    // Boost ML retrofit (Layer A): on-device LightGBM risk scores. Logged to NS for
    // observability; not yet consumed by dosing logic (Layer B+ wires consumption).
    var mlHypoRisk: Double? = null,              // P(hypo event in next 4h), 0.0-1.0
    var mlMealLikely: Double? = null,            // P(BG peak >= +50 mg/dL in next 90 min), 0.0-1.0

    // Boost ML retrofit (Layer B): consumption fields.
    var mlRiskScale: Double? = null,             // SMB-size scaling factor derived from mlHypoRisk (1.0 = no scaling, 0.0 = full block)

    // Boost ML retrofit (Layer C): G3 pre-UAM uncertainty hold telemetry.
    var mlMealG3Released: Boolean? = null,       // True iff G3 hold conditions met AND a release condition fired
    var mlG3ReleaseSource: String? = null,       // Which release condition fired: "delta_accl" | "bg_threshold" | "meal_model"

    // Boost V5 silent-shadow input bridge: V1/V2 expose their internal minGuardBG
    // here so V5's hard-gate check (against the LGS threshold) uses the same
    // predicted-low V1/V2 chose. Null falls back to current BG inside V5 — safe
    // but more permissive than the V4.4.x-native behaviour.
    var minGuardBG: Double? = null,

    // Boost V5 silent-shadow telemetry. V5 runs as an observer alongside V1/V2's
    // acting algorithm; these fields capture what V5 would have done if it were
    // driving. They do not influence dosing. See OpenAPSBoostV5Plugin.runShadow().
    var boostV5_score: Double? = null,           // meal_signal_score 0.0-1.0
    var boostV5_state: String? = null,           // IDLE | OBSERVING | CONFIRMED | COMMITTED | RECOVERING
    var boostV5_age: Int? = null,                // cycles in current state
    var boostV5_budget: Double? = null,          // aggression_budget U
    var boostV5_actionMult: Double? = null,      // action multiplier for the current state
    var boostV5_finalDose: Double? = null,       // V5's would-have-delivered SMB (U) — direct comparator to rT.units
    var boostV5_gateReduction: String? = null,   // compact summary of which Phase 3 gates fired

    // HR + sleep telemetry (2026-06-02) — emitted into NS devicestatus for retrospective
    // sleep-model tuning. Cadence = one observation per Boost cycle (~5 min).
    var hrBpmLatest: Double? = null,                 // most recent HR reading at cycle time
    var hrBpmAvg5m: Double? = null,                  // duration-weighted average over last 5 min
    var hrBpmAvg15m: Double? = null,                 // duration-weighted average over last 15 min
    var hrReadingsCount15m: Int? = null,             // number of HR records seen in 15-min window
    var sleepState: String? = null,                  // AWAKE | PRE_SLEEP | SLEEPING
    var sleepStateEnteredAtMs: Long? = null,         // when current sleep state was entered (UTC ms)
    // 28-day learned sleep schedule — null until ≥7 sessions recorded
    var sleepLearnedStartMin: Int? = null,           // circular-mean sleep-onset clock-min (0..1439)
    var sleepLearnedWakeMin: Int? = null,            // circular-mean wake clock-min (0..1439)
    var sleepLearnedDurationMin: Int? = null,        // mean sleep duration (min)
    var sleepLearnedSessionCount: Int? = null,       // sessions in 28-day window
    // Learned HR baselines (median of per-session p10, ≥7 valid sessions)
    var hrLearnedRestingBpm: Int? = null,            // true resting (deep-sleep floor)
    var hrLearnedDaytimeBpm: Int? = null,            // active-baseline (used by exercise calcs)

    // Boost ISF shadow telemetry — V4.4.2-style TDD-anchored EMA(τ=3h) sensitivity ratio
    // computed in parallel with V1/V2's instantaneous ratio so the EMA overlay's actual
    // contribution can be measured without changing dosing.
    var isfShadow_ratioRaw: Double? = null,          // raw tdd_24h / tdd_7d (also what V1/V2 use today)
    var isfShadow_ratioEma: Double? = null,          // V4.4.2's smoothed ratio (bounded by autosens)
    var isfShadow_warmup: Double? = null,            // 0.0-1.0, cold-start blend factor
    var isfShadow_variableSens: Double? = null,      // implied variable_sens if the EMA ratio had been used (mg/dL/U)
    var isfShadow_insulinReq: Double? = null,        // implied insulinReq under shadow variable_sens (U)
    var isfShadow_microBolus: Double? = null,        // implied microBolus under shadow insulinReq, same tier (U)
    var isfShadow_deltaPct: Double? = null,          // (shadow/actual - 1) × 100 on variable_sens — single-number summary

    // Activity-load SHADOW (2026-06-16): personal step baseline + what an activity/inactivity ISF
    // modifier WOULD apply. LOGGED ONLY; never affects dosing. See DailyStepHistoryTracker.
    var boostActivityLoad_baselineSteps: Int? = null,   // personal median daily steps (single source)
    var boostActivityLoad_lastDaySteps: Int? = null,    // yesterday's single-source total
    var boostActivityLoad_ratio: Double? = null,        // decay-weighted recent load ÷ baseline
    var boostActivityLoad_wouldDeltaIsfPct: Double? = null, // signed: + raise ISF (activity) / − lower (inactivity)
    var boostActivityLoad_source: String? = null,       // chosen HC step source package
    // Intraday activity-load SHADOW (2026-06-19): today's cumulative steps vs typical pace by hour.
    var boostActivityLoad_stepsToday: Int? = null,      // cumulative steps since local midnight (phone)
    var boostActivityLoad_intradayRatio: Double? = null, // stepsToday ÷ expected-by-now
    var boostActivityLoad_intradayDeltaIsfPct: Double? = null, // raise-only would-ΔISF from intraday pace
    var boostActivityLoad_stepsSource: String? = null,  // "wear" (worn AAPS Wear watch) | "phone" (pedometer)

    // Autosens / TDD-DynISF coordination telemetry (2026-06-16). Which mechanism drives basal +
    // the would-be alternative, so ApsBoostAutosensWhenNoTdd can be validated before being enabled.
    var boostAutosens_mode: String? = null,              // "tdd" | "autosens" | "curve"
    var boostAutosens_orefRatio: Double? = null,         // real oref autosens ratio (1.0 if autosens off)
    var boostAutosens_curveRatio: Double? = null,        // legacy DynISF-curve ratio
    var boostAutosens_appliedRatio: Double? = null       // ratio actually passed to determine_basal
) {

    fun serialize() = Json.encodeToString(serializer(), this)

    object StringBuilderSerializer : KSerializer<StringBuilder> {

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringBuilder", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: StringBuilder) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(decoder: Decoder): StringBuilder {
            return StringBuilder().append(decoder.decodeString())
        }
    }

    object TimestampToIsoSerializer : KSerializer<Long> {

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LongToIso", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Long) {
            encoder.encodeString(toISOString(value))
        }

        override fun deserialize(decoder: Decoder): Long {
            return fromISODateString(decoder.decodeString())
        }

        fun fromISODateString(isoDateString: String): Long {
            val parser = ISODateTimeFormat.dateTimeParser()
            val dateTime = DateTime.parse(isoDateString, parser)
            return dateTime.toDate().time
        }

        fun toISOString(date: Long): String {
            @Suppress("SpellCheckingInspection", "LocalVariableName")
            val FORMAT_DATE_ISO_OUT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            val f: DateFormat = SimpleDateFormat(FORMAT_DATE_ISO_OUT, Locale.getDefault())
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(date)
        }
    }

    companion object {

        private val serializer = Json { ignoreUnknownKeys = true }
        fun deserialize(jsonString: String) = serializer.decodeFromString(serializer(), jsonString)
    }
}