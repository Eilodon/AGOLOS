ZenB Core Engine Design

Overview
- Modules: estimator, safety, controller, phase_machine, breath_engine, engine
- Determinism: time represented as integer microseconds (i64 ts_us and u64 dt_us in ticks). Core state hash uses blake3 over stable JSON serialization of `BreathState`.

Estimator
- Lightweight EMA-based estimator using dt-aware alpha.
- Input features: [hr_bpm, rmssd, rr_bpm]. Missing values handled.

Context & features (runtime):
- The engine expects sensor `features` arrays in the following order when ingesting from clients: `[hr_bpm, rmssd, rr_bpm, quality, motion]`.
  - `quality` and `motion` are optional (defaults: quality=1.0, motion=0.0).
- The App (React Native / iOS / Android) should pass current context to core via `update_context` (or call `ingest_sensor_with_context`) containing `local_hour`, `is_charging` and `recent_sessions`.
- This ensures ContextualPathway and BiometricPathway have accurate time/charging/motion info.
- Outputs smoothed hr, rr, rmssd and a confidence in [0,1].

SafetyEnvelope
- Configurable protections: rr_min/rr_max, max_rr_delta_per_min, max_hold_us, min_confidence, min_update_interval_us.
- If confidence < min_confidence, adaptations are frozen.

AdaptiveController
- Decides a target breath rate (bpm) based on estimator and last decision.
- Enforces a minimum decision interval and epsilon threshold to avoid tiny oscillatory patches.

PhaseMachine & BreathEngine
- Splits cycle into Inhale/HoldIn/Exhale/HoldOut according to percentages.
- `tick(dt_us)` consumes time deterministically; phase transitions are ephemeral and not persisted.

Persistence strategy
- Only persist: SessionStarted, SensorFeaturesIngested (downsampled 1-2Hz), ControlDecisionMade (on change or ~2Hz), PatternAdjusted (on patch), CycleCompleted (rare), SessionEnded, Tombstone.
- High-frequency tick events are kept in RAM only.

Batching policy (in `zenb-uniffi::Runtime`)
- Buffer events in memory and flush when any of the following are true:
  - len >= 20
  - bytes >= 64 KB
  - elapsed >= 80 ms since last flush
  - force flush (explicit flush or EndSession)
- `append_batch` validates sequence with single SELECT (no per-row queries).

Security notes
- Cryptography handled by `zenb-store` using XChaCha20-Poly1305 with metadata AAD including meta hash.
- Session keys are wrapped with the master key and zeroized when dropped.

