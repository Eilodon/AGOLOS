Runtime and Persistence

This crate provides a small runtime (`Runtime`) that glues `zenb-core` Engine to `zenb-store` for persistence and `zenb-projectors` for view updates.

Batching policy
- Events are buffered in memory and flushed to the store when:
  - buffer length >= 20
  - buffer bytes >= 64 KB
  - elapsed >= 80 ms since last flush
  - explicit flush

Downsampling & persistence
- Sensor features are ingested into the engine every tick, but are only persisted at ~2Hz to avoid DB bloat.
- Control decisions are persisted on meaningful changes or at ~2Hz.
- High-frequency phase ticks are kept ephemeral in RAM and not persisted.

Security
- The `EventStore` encrypts payloads per-event with session-specific keys wrapped by the master key.
