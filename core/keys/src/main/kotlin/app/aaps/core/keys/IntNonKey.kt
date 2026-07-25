package app.aaps.core.keys

import app.aaps.core.keys.interfaces.IntNonPreferenceKey

@Suppress("SpellCheckingInspection")
enum class IntNonKey(
    override val key: String,
    override val defaultValue: Int,
    override val exportable: Boolean = true
) : IntNonPreferenceKey {

    ObjectivesManualEnacts("ObjectivesmanualEnacts", 0),
    RangeToDisplay("rangetodisplay", 6),

    // Boost V5/V6 auto-config persistence schema version (see BoostV5AutoConfigApply.
    // AUTO_CONFIG_SCHEMA_VERSION): bumped when the resolution semantics change so already-persisted
    // per-knob resolved flags can be re-audited (versioned re-migration). 0 = pre-versioning.
    BoostV5AutoConfigSchemaVersion("boost_v5_autoconfig_schema_version", 0)
}