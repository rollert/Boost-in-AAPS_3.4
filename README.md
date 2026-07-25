# Boost V6 — experimental AndroidAPS fork

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/aUzQ8q5zQd)

> ⚠️ **Experimental. Not medical advice. Not a released or approved product.**
> This is a developer's research fork of AndroidAPS that changes the automated insulin-dosing
> decision. Do not run it on a pump unless you fully understand the code, accept the risk, and can
> self-manage the consequences. **You are the safety system.**

This page covers **what is different in Boost V6, how it works, and its settings.** The detailed
settings reference for the earlier plugins lives on a separate page:
**[Boost V1 / V2 / v4.2 — legacy settings reference](docs/boost-v1-settings.md)**. The data-analysis
method that lets a live dosing algorithm be changed safely has its own page too:
**[backtesting — safe algorithm updates & shadow validation](backtesting/README.md)**.

### 🧮 Interactive tools (open in a browser)

Three self-contained HTML tools — no install, no data leaves your machine — let you *see* how Boost
doses and how V1 and V6 differ, side by side:

- **[▶ Boost Simulator](https://tim2000s.github.io/Boost-in-AAPS_3.4/boost_simulator.html)**
  ([source](boost_simulator.html)) —
  a live what-if for the dosing maths. Set BG, trend, IOB, TDD and the settings (or pull a snapshot
  from Nightscout) and watch the ISF and SMB recompute. **Two tabs, one per engine**: the V1/V2
  **8-tier** ladder and the **V6 meal state-machine**, each with its own settings, dose breakdown and
  BG projection (the V6 tab keeps a one-line *"V1 would deliver"* reference, because V6's own
  non-meal cap depends on it). Faithful JS ports of the real engine (`MealSignalScore`,
  `MealHypothesis`, `AggressionBudget`, `SafetyGates`), including the July-2026 safety layer.
- **[▶ Boost Tuning Guide](https://tim2000s.github.io/Boost-in-AAPS_3.4/boost_tuning_guide.html)**
  ([source](boost_tuning_guide.html)) —
  a visual reference for what every setting does to aggressiveness: separate **V1** and **V6** tabs,
  each knob on a conservative→aggressive spectrum, real-world tuning scenarios per engine, and the
  V6 tab explaining the state-machine model and how V1's ~24 knobs collapse to V6's **3**.

- **[▶ Boost Analyser](https://tim2000s.github.io/Boost-in-AAPS_3.4/boost_analyser.html)**
  ([source](boost_analyser.html)) —
  **V1 vs V6 on *your own* data.** Enter your Nightscout URL + a read token and it reads the shadow
  telemetry every Boost build logs each cycle (what your engine delivered *and* what the other engine
  decided on identical inputs) — a real, paired comparison, not a simulation. Shows your actual
  TIR/TING, per-cycle dose deltas, night-vs-day splits, auto-detected **meal episodes** with the V6
  state ribbon and both dose traces, confirm latency, and which safety gates fired. Runs entirely in
  your browser: the token goes only to your Nightscout.

> These tools validate **decisions** (what dose, which state, why) — not glucose outcomes. They model
> the algorithm, not a body. For the data-driven validation method see the backtesting page above.

---

## Contents

1. [What Boost V6 is — and what's different](#1-what-boost-v6-is--and-whats-different)
2. [How it runs — the shadow-vs-active safety gate](#2-how-it-runs--the-shadow-vs-active-safety-gate)
3. [How it works — the dosing core and the learners](#3-how-it-works--the-dosing-core-and-the-learners)
4. [Auto-configuration (first activation)](#4-auto-configuration-first-activation)
5. [Settings reference (V6)](#5-settings-reference-v6)
6. [Heart rate, steps & night mode](#6-heart-rate-steps--night-mode)
7. [Backtesting, "no training", and robustness](#7-backtesting-no-training-and-robustness)
8. [Testing & evidence](#8-testing--evidence)
9. [Legacy V1 / V2 / v4.2 settings](#9-legacy-v1--v2--v42-settings)

---

## 1. What Boost V6 is — and what's different

Boost keeps the **entire AndroidAPS engine** — basal, DynISF / `future_sens`, glucose predictions and
**every safety gate** — and replaces **only the SMB (super-micro-bolus) decision** with a meal-aware
state machine plus a layer of personal context (activity, heart rate, sleep). Nothing else about how
AndroidAPS runs your pump is touched.

The single difference that matters: **stock AndroidAPS sizes one isolated micro-bolus each cycle, from
scratch. Boost V6 carries a *meal hypothesis* across cycles and scales dosing to its confidence.**

`IDLE → OBSERVING → CONFIRMED → COMMITTED → RECOVERING`

- **OBSERVING** — a rise is building; dose lightly while evidence accrues.
- **CONFIRMED** — a meal is recognised (BG delta + acceleration + an ML meal-likelihood score +
  time-of-day + sustained-rise + not-exercising, minus a recent-low penalty); deliver the catch-up commit.
- **COMMITTED** — hold a measured per-cycle dose while the meal is clearly active.
- **RECOVERING** — **deliberately wind down** as insulin takes hold, instead of re-deciding from
  scratch and re-dosing a meal that's already handled.

On top of the dosing core, V6 adds **learners** that personalise *sensitivity and timing* (never the
safety limits): a **heart-rate / step** feed (activity load + sleep detection), a **sleep-window**
learner, and **meal-time** learning.

> 👁️ **Want to see the guts of V6 working? Use the Boost Overview V2.** Enable
> **"Use Boost Overview V2 (dark theme)"** in the Overview plugin settings (switch tabs or restart the
> app for it to take effect). It is the live window into V6's internals: the **meal-hypothesis state**
> (IDLE → OBSERVING → CONFIRMED → COMMITTED → RECOVERING) with its action multiplier, meal score and
> aggression budget; the **active brakes / safety gates**; DynISF, IOB and TDD; the activity / exercise
> state; and a **steps + heart-rate** graph. It is the at-a-glance answer to *"why is V6 dosing the way
> it is right now?"* — the recommended place to watch the algorithm work. The classic V1 overview keeps
> working if you prefer it.

> Where this came from: V6 is the current generation of a line that ran V1 → V2 → V3 → v4.4 → v4.4.2 → V6 (still named "V5" internally in the code). The
> earlier plugins and their settings are documented in the
> [legacy settings reference](docs/boost-v1-settings.md). V6 still runs *on top of* the V1 engine and
> derives its day-one defaults from your V1/oref history (see §4).

---

## 2. How it runs — the shadow-vs-active safety gate

Boost is selected as your APS plugin. **Active dosing is opt-in by which plugin you pick:**

| APS plugin you select | What drives your pump |
|---|---|
| (any non-Boost engine) | unchanged — Boost not involved |
| **"Boost"** | the shared engine with the V6 override in **shadow** — it computes what it *would* do and logs it to Nightscout, but it does **not** drive the pump |
| **"Boost V6"** | **active** — the state machine drives the SMB |

A freshly built copy does **not** auto-dose — you must deliberately select the "Boost V6" plugin.
**The supported path for anyone but the developer is shadow first:** run "Boost", watch what it *would*
have done in Nightscout for a couple of weeks, then decide. This is not a disclaimer — it is the
designed onboarding (see §7).

---

## 3. How it works — the dosing core and the learners

### The dosing core (the meal-hypothesis state machine)

Two cascade controls bound the state machine above:

- an **aggression budget** — a hard ceiling on insulin delivered per rise/"burst", and
- a **deceleration brake** — eases off the moment BG stops *accelerating*, so Boost stops adding
  insulin to a meal that's already turning.

Two risk inputs pull dosing back *before* trouble, not after:

- an **ML hypo-risk score** throttles the aggression budget — higher modelled risk allows *less*
  insulin (it can only ever reduce delivery — see §7), and
- a **recent-low penalty** damps the meal-confirm score for a window after any low.

Four further guards (July 2026) bound the state machine's edges:

- in **non-meal states** (IDLE / OBSERVING / RECOVERING) V6 **never doses more than V1 would** on the
  same inputs — only a confirmed meal hypothesis can out-dose V1;
- the one-per-meal CONFIRMED catch-up shot is only spent when the velocity-scaled dose would **beat a
  routine hold cycle** — otherwise V6 keeps observing rather than burning its confirm on a trivial
  upswing;
- the single-cycle **fast-carb confirm is suppressed for an hour after any BG below 80**, so a
  rescue-carb rebound is never treated as a new meal; and
- for **45 minutes after any BG below 75**, even a confirmed meal hypothesis **can't out-dose the
  hypo-restrained V1 base** — a rescue-carb rebound inherits V1's post-rescue restraint instead of
  drawing a full V6 catch-up shot.

Every stock AndroidAPS safety gate still runs underneath — most importantly the hard
**`minGuardBG ≥ 80`** gate, which blocks dosing into a projected low regardless of any Boost setting.

### DynISF / `future_sens`

V6 uses the AndroidAPS dynamic-ISF path. The settings (normal target, BG cap, velocity, adjustment
factor) shape the dynamic-ISF curve and how far ahead it projects; the activity learner (below) nudges
*sensitivity* around the user's own baseline rather than overriding the curve.

### The learners (personal context)

These shape **sensitivity and timing only** — never the guardrails:

- **Activity load** — a personal daily-step baseline; high-activity days raise ISF, sedentary days
  lower it. *(Currently shadow — logs what it would do; see §5/§8.)*
- **Heart rate & sleep** — see §6.
- **Meal-time learning** — an anticipatory pre-meal target around habitual meal times. *(Shadow.)*

> Design rule for every learner: **learn the user's personal baseline, act on *deviation* from it, keep
> the clinical absolutes fixed.** Personalise the dials (sensitivity, activity response); never the
> guardrails (hypo thresholds, min-guard, max-IOB, hard gates). Blend with autosens rather than
> stacking on top of it.

---

## 4. Auto-configuration (first activation)

The first time V6 runs active, Boost **seeds its settings from your own recent dosing history** (last
14 days) rather than dropping you onto generic defaults. The principle (from the shadow-equivalence
work in §7): dose calibration is *co-adapted to the individual*, so the safe onboarding is to **start
where your prior dosing left off** and tune from there — not a cold jump to a stranger's numbers. It
works from **any prior engine** (standard oref/AndroidAPS, not just Boost), since it reads only dosing
history + glycaemia.

**The guard-rails:**
- Runs **once**, in the background, the first cycle V6 is active (one-shot flag).
- **Suggestion-only** — writes a setting **only if you haven't already changed it** from a factory
  default (*any* factory default that setting ever shipped with, so a value carried over from an
  older build still counts as untouched). It never overrides anything you've tuned.
- Needs **≥ 7 days of data and ≥ 1500 CGM readings**; otherwise does nothing and **retries later**.
- **Never auto-raises aggression** above neutral on day one; safety knobs only ever *tighten*.
- **Wrapped so any failure is logged and swallowed** — it can never block or alter the dose path.
- It **logs the full reasoning and notifies you** of exactly what it set and why.

### How it determines each setting (the exact rules)

Over the last 14 days it gathers: your **true TDD** (basal + bolus), your **bolus and SMB sizes** (meal
boluses vs SMBs), your **time-below-range** (% < 70 and % < 54 mg/dL), and your **max-IOB / max-bolus**
limits. Then:

| Setting (range) | Rule |
|---|---|
| **HypoCaution** (1.0–2.0) | `clamp(1.0 + max(0, TBR<70% − 4)/4 + max(0, TBR<54% − 1)×0.5, 1.0, 2.0)` — climbs above 1.0 only as time-low exceeds the consensus targets (4% / 1%). |
| **Aggression** (0.7–1.3) | `0.85` if hypo-prone; `0.92` if TBR<70% > 4%; else **1.0**. Never set above 1.0. |
| **Confirmed cap** (0–7.5 U) | `clamp(max(p90 of meal boluses, p95 of SMBs), 1.5, 7.5)` — covers your biggest *typical* single dose so real meals aren't clipped. The meal-bolus p90 only participates with **≥ 10 manual boluses** in the window (a percentile of a handful of boluses is noise, not a habit); below that the cap comes from the SMB p95 alone. |
| **Committed cap** (0–2.5 U) | `clamp(max(p75 of SMBs, TDD/40), 0.25, 2.5)` — your routine per-cycle hold (whichever of the two terms is larger). |
| **Cumulative SMB cap / 60 min** (≥ 1 U) | `clamp(Confirmed cap + 2×Committed cap, 1.0, 10.0)` — bounds dose *frequency*: one confirm shot plus two holds per hour, clamped only to the preference range. Computed from the **final operative** per-shot caps (kept-or-derived), so a kept user value sizes the hourly budget, not a derivation that never applied. |
| **Max IOB / Bolus cap** | carried from your existing limits (clamped to range). |
| **Fast-carb confirm** | **off** if hypo-prone, otherwise on. |

**TBR raise-guard:** a dose-cap **raise** (Confirmed / Committed / Cumulative going *up* from the
current value) is **not auto-applied when 14-day time-below-70 exceeds 4%** — it is surfaced as a
suggestion in the notification instead (set manually in Advanced if desired). Lowerings and all
non-cap tightenings always apply.

"Hypo-prone" = TBR<54% > 1.5% **or** TBR<70% > 6%. A well-controlled user lands on a fully neutral
config (Aggression 1.0, HypoCaution 1.0, fast-carb on); a low-prone user gets gentler aggression, more
hypo damping, tighter caps, and fast-carb off — all conservative. Aggression can only be matched
*precisely* once shadow data has accrued, so the day-one value is deliberately cautious and is the one
knob most worth reviewing after a couple of weeks.

**Validation.** The derivation was checked against **12 real users** from a research database (an
OpenAPS/Trio cohort and an AndroidAPS cohort, 400–720 days each): the rules were applied to each user's
real history to produce the knobs, those knobs were run through the **V6 engine over the user's own
logged cycles**, and the dosing was probed for danger. **Result: no dangerous dosing** — dose-into-low
≤ 0.2% (the `minGuardBG ≥ 80` gate blocks it regardless of knobs); well-controlled users ran at neutral
V6; for hypo-prone users the protective knobs **reduced** dose-into-low events 15–24%. In no case did
auto-config make dosing *more* aggressive than the engine's default. *(That replay is open-loop — it
does not feed V6's doses back into glucose — so absolute insulin totals from it are inflated artifacts,
not real closed-loop amounts.)*

**Hardening (2026-06-26 adversarial review).** A multi-perspective review of auto-config and the
active-override path (Android + the Trio port) closed three things: the cumulative-60-min cap is now
re-checked on the V6 active-override path itself (a V6 override could previously bypass the base-engine
check); V6's IOB headroom is clamped to the system/oref max-IOB; and the cumulative-cap ceiling was
raised to track the Confirmed cap. None changed the derivation; all tightened the safety envelope. The
derivation was checked line-for-line against the Trio (Swift) port and is in full parity.

---

## 5. Settings reference (V6)

All Boost settings live under the plugin preferences. Defaults shown; most are auto-seeded (§4).

> **Simple Mode note:** the Boost preference *screen* is still hidden while AndroidAPS is in Simple
> Mode, but your saved Boost dosing settings now **still apply** in Simple Mode — Boost reads them via a
> dedicated bypass, so they are no longer masked back to factory defaults as they once were.

**Dosing**
- **Aggression** `0.7–1.3` (1.0) — scales the **CONFIRMED catch-up shot** (the state machine's one
  discretionary dose); routine holds are bounded by the caps below, not this knob.
- **HypoCaution** `1.0–2.0` (1.0) — scales the aggression budget down; higher = more hypo-defensive.
- **Sensitivity** `0.8–1.2` (1.0) — scales the **aggression budget** (below 1.0 for sensitive users, above for resistant); it is not a DynISF multiplier.
- **CONFIRMED dose cap** `0–7.5 U` (2.5) — hard limit on the meal-confirm commit shot.
- **COMMITTED dose cap** `0–2.5 U` (0.5) — hard limit on the per-cycle holding SMB.
- **Cumulative SMB cap / 60 min** `0–10 U` (10) — rolling-hour ceiling across all SMBs. The factory
  default is deliberately non-binding; auto-config (§4) tightens it to your history. `0` disables it.
- **Max IOB** `0.1–12 U` and **Bolus cap** `0.1–10 U` — overall Boost insulin limits.
- **Fast-carb confirm** (on) — single-cycle confirm on a sharp, accelerating, score-corroborated rise.
- **Phase-3 composed brake floor** (off) — enforces a 25% floor on the composed soft-brake multiplier
  during active meal sessions above 160 mg/dL with eventualBG above target, fixing the soft-brake
  stack compounding to sub-pump-step zero doses mid-meal (July 2026). All hard gates and dose caps
  still apply. **Per-user activation only**: enable only if trailing 14-day time-below-range is
  within consensus targets (<63 mg/dL below 2.0% **and** <70 mg/dL below 3.5%) — do not enable outside those.
  The gate is self-updating and fail-closed: it auto-holds the floor the moment either 14-day figure crosses its limit.

**V6 DynISF / `future_sens`**
- **DynISF normal target** (99 mg/dL), **BG cap** (210), **velocity** (100), **adjustment factor** —
  shape the dynamic-ISF curve and how far ahead it projects.

**V6 activity (currently shadow — logs what it *would* do)**
- **Activity / inactivity %** and the **step thresholds** (5/15/30/60-min) — learn a personal
  daily-step baseline and would raise ISF on high-activity days / lower it on sedentary ones.

**V6 heart-rate, sleep & night mode** — see §6.

**Post-exercise recovery** — optional gentler target and dosing scale for a configurable window after
detected exercise.

> The full per-control reference for the underlying V1/V2 plugins (DynISF V1/V2 formulae, tier system,
> Boost start/end times, UAM Boost tiers, Acceleration Bolus, step-count features, BG-source warnings)
> is on the **[legacy settings page](docs/boost-v1-settings.md)**.

---

## 6. Heart rate, steps & night mode

Boost reads **heart rate and steps from a wear device** (via Health Connect, with a Wear OS step
bridge) and uses them to detect sleep and shape overnight dosing — there is **no fixed clock window
doing the dosing**; the clock only sets a broad outer band.

**Sleep detection** (`SleepStateDetector`, 3 states):
- **PRE_SLEEP** — a time-only pre-warm window before your configured night-start (lead default 60 min).
  It engages night-mode SMB suppression *proactively* so you don't carry excess IOB into the night.
- **SLEEPING** — entered when, together and held for a hysteresis: HR within ~15% of resting HR, steps
  near-zero, inside the outer night band, and no meal imminent. Because the HR feed can be
  intermittent, a *drought* of HR transmissions also counts as a sleep signal.
- **AWAKE** — exit requires a **genuine wake**: an HR rise **and** step activity (a BG rise alone
  doesn't wake it — REM can lift HR without waking you).

**Learned night window** (`SleepHistoryTracker`): learns your personal sleep-onset and wake times over a
rolling 28-day window, but the wake boundary is **anchored to your configured night-end and only allowed
to move ± 90 min**, and only learns from *genuine* HR/step wakes. This anchoring stops a feedback loop
that used to ratchet the learned wake ever-earlier when overnight HR data was sparse.

**What night mode does** (`ApsBoostNightModeEnabled`, optionally auto-triggered by sleep detection
rather than a clock): it **suppresses SMB** while you sleep — `isSMBModeEnabled` returns false, so the
loop runs **basal/temp-basal only** and the V6 meal-hypothesis override is gated off too. There is **no
target raise**: the configurable BG offset (default 27 mg/dL) is an *activation gate*, not a target —
night mode only suppresses while BG is below `profileTarget + offset`, so if you're running high it
lets SMB correct. So overnight Boost runs gentle and basal-led, then resumes full behaviour on a
genuine wake. Optional guards disable night mode if carbs are on board or a low temp-target is set.

---

## 7. Backtesting, "no training", and robustness

This is the part that makes changing a *live* dosing algorithm defensible. The full method and tooling
are on the **[backtesting page](backtesting/README.md)**; the essentials:

### There is no training loop in the dose path — "no training"

This is the point people most often get wrong about Boost, so it's stated plainly:

- **The dose decision is a deterministic, rule-based state machine.** It is *not* a model trained to
  output insulin. Nothing in the dosing path is fit to data, learned online, or a black box. Given the
  same inputs it produces the same dose, and every branch is readable in source.
- **Two small on-device trained models feed the decision — neither outputs insulin.** The
  **hypo-risk score** (a gradient-boosted tree validated **leave-one-user-out**, so it is scored on
  users it never saw in training) throttles the aggression budget and can only ever *reduce*
  delivery. The **meal-likelihood score** is one bounded input (weight 0.20, renormalised away when
  the model is unavailable) into the otherwise rule-based meal-confirm score — it can help the state
  machine recognise a meal *earlier*, but every dose that follows passes the same caps and gates, and
  in non-meal states V6 remains capped at what V1 would do. Neither model can *add* a dose or relax a
  limit.
- **Personalisation ≠ training.** Auto-config (§4) and the learned baselines (§6) derive *suggestions*
  from **your own history** — they tune settings, they do not learn the dose. Auto-config is
  suggestion-only, one-shot, and only ever tightens safety knobs.
- **Validation is replay on real history, not curve-fitting.** Candidate changes are scored against
  real recorded decisions before any dosing code ships (below) — there is no parameter sweep optimising
  a glucose objective on the same data, which is exactly how dosing algorithms overfit.

### Why changing a dosing algorithm is treated as a clinical-equivalence problem

Users **co-adapt** to an algorithm's behaviour (manual pre-boluses, knob settings, meal habits). A
"correct" fix can make control *worse* until the user re-adapts. So every change is framed by the
taxonomy in **arXiv 2606.13882v1, "Safe Algorithm Updates in Automated Insulin Delivery Systems"** and
classified before it ships:

| class | meaning | how it is treated |
|---|---|---|
| **Factual** | objective, wrong-by-computation | fix immediately (e.g. an inverted knob, a null-returning method) |
| **Heuristic** | co-adapted with the user's behaviour | transition **gradually, shadow-first** (e.g. dose aggressiveness, meal-confirm timing) |
| **Computational** | numeric / port differences | verify **equivalence** (e.g. the Android↔Trio port) |

**The bar:** a change should be *clinically equivalent or better* — validated on real history — before
it doses for anyone. Two rules fall out of this: **don't flash an unvalidated dosing change right before
the user is away** (if it can't be watched, it doesn't ship — unless it's pure shadow); and
**shadow-first for anything heuristic.**

### The backtesting toolkit (`backtesting/`, reproducible on real Nightscout data)

All scripts read Nightscout `devicestatus`, which already logs **paired** outputs (V1's actual dose,
V6's shadow/active decision, the `V1 would=` counterfactual, the ISF-shadow overlay), so they
reconstruct decisions from data we already have rather than re-implementing the algorithm:

| script | what it answers |
|---|---|
| **`shadow_equivalence.py`** | Per-component agreement/divergence between two algorithm paths. "How different is the change, and where?" Divergence concentrates in meal cycles; basal is identical. |
| **`replay.py`** | Re-runs a **candidate change** over real history and scores it (meals caught earlier vs false fires vs sleep fires) — lets us reject unsafe designs **before** writing dosing code. |
| **`parkes_grid.py`** | Parkes Error Grid of Boost's **predicted** BG vs the BG that **actually occurred** — forecast accuracy (Type-1 zone boundaries exact, Pfützner 2013). |
| **`episode_impact.py`** / **`cold_idle_dose_validation.py`** | First-order / counterfactual BG-impact estimates around real low/high episodes — quantify the trade a change makes (open-loop, clamped, not a simulation). |

**Worked example — the fast-carb fast-path (2026-06-16).** Observed a fast carb spike-then-crash where
V5 sat in OBSERVING one cycle too long. Classified *heuristic*. Designed a one-cycle promotion on a
sharp accelerating rise. **Replay rejected the obvious rule** — it fired during sleep and ~2×/day
falsely; adding corroboration (require the meal score *and* awake *and* not-exercising) gave zero sleep
fires, half the false rate, still caught ⅓ of meals ~15 min earlier. **The replay chose the safe design
before any dosing code was written.** A separate proposed cold-IDLE fast-path was likewise **reverted**
after a full-cohort re-run didn't support it.

### Robustness, in one list

- **Every stock AndroidAPS safety gate is unchanged** — Boost only replaces the SMB decision; the hard
  `minGuardBG ≥ 80`, max-IOB and max-bolus gates all still run underneath.
- **Shadow mode is a real execution path**, not a simulation — the same code runs and logs without
  touching the pump, so what you watch *is* what would dose.
- **Auto-config is suggestion-only, one-shot, failure-swallowing**, and only ever tightens safety knobs.
- **Caps are layered**: per-shot magnitude caps (Confirmed/Committed) *and* a rolling-hour cumulative
  cap on frequency, the latter now enforced on the override path too and clamped to the system max-IOB.
- **Android and the Trio (Swift) port are kept in numeric parity**, checked line-for-line.
- **What is *not* claimed:** there is **no glucose-outcome simulation** (UVA-Padova-style virtual
  patients) and **no Parkes-grid clinical-equivalence pass on simulated glucose**. The tools validate
  *decisions and forecasts*, plus real single-user outcomes — not a population glucose-outcome
  guarantee. **For everyone but the developer, shadow is the supported mode.**

---

## 8. Testing & evidence

A single developer running V6 **active** on their own pump for ~5 months, plus a small cohort running it
in **shadow**. **This is real-world experience and shadow analysis, not a clinical trial.**

**Developer's own V6-active glycaemia** (honest, full picture):
- **Time in range (70–180): ~85%**, mean ~6.9 mmol/L.
- **Normal weeks: within hypo targets** — TBR<70 ~2.5–3%, severe <54 < 0.5%.
- **Very-high-activity weeks** (multi-day festival / heavy training): **hypo above target** — TBR<70
  7–8%, severe <54 2–3.5%. This is **exercise-into-correction** (a correction firing into an
  already-falling, activity-driven BG), not a baseline dosing fault; the activity-load ISF mitigation
  (§5) is in shadow and is the next thing to land. **Watch this if you run it through heavy exercise.**

Period reports live in `backtesting/` (`SHADOW_EQUIVALENCE_REPORT.md`, `V5_VS_V1_SUMMARY.md`,
`EPISODE_IMPACT_REPORT.md`, `IDLE_FASTPATH_REPORT.md`).

---

## 9. Legacy V1 / V2 / v4.2 settings

The detailed, per-control reference for the earlier plugins — DynISF V1/V2 formulae and when to use
each, the tier system, Boost start/end times, UAM Boost tiers, the Acceleration Bolus, Night Mode
settings, step-count features, the on-device ML hypo-risk model details, and the BG-source safety
warning — has moved to keep this page focused:

**→ [Boost V1 / V2 / v4.2 — legacy settings reference](docs/boost-v1-settings.md)**

---

*Boost is a personal experiment shared in the open-source loop tradition. Nothing here is medical
advice; decisions about your diabetes are yours and your clinician's.*
