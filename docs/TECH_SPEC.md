# ZenB Tech Spec (v3 minimal)

- Events: SessionStarted, SensorFeaturesIngested (downsampled), ControlDecisionMade, PatternAdjusted, CycleCompleted, SessionEnded, Tombstone
- AAD layout for payload encryption: session_id(16) || seq(u64 LE) || event_type(u16 LE) || ts_us(i64 LE) || meta_hash(32)
- Row schema: see `crates/zenb-store::init_schema`
- Master key: provided by host (env or provisioning). For skeleton tests, deterministic master key is used.
- Crypto-shredding APIs: `create_session_key`, `load_session_key`, `delete_session_keys`.
- Determinism: `zenb-core::replay_envelopes` validates sequence and produces deterministic state and hash via BLAKE3.
