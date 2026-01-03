# Belief Engine (Quantum-Belief)

This document describes the new Belief Engine and Swarm Safety modules added to the core.

- Belief State: a 5-way distribution (Calm, Stress, Focus, Sleepy, Energize) with aggregate confidence and a collapsed mode via hysteresis.
- Pathways: deterministic ensemble of rule-based pathways (Logical, Contextual, Biometric).
- Fusion: prior + Σ(w_i * out.conf * out.logits), softmax -> EMA smoothing, hysteresis collapse.
- Swarm Safety: Guards (hard vetoes and soft clamps) vote on proposed PatternPatch; any Deny rejects; clamps intersected deterministically.

Integration:
- `Engine::ingest_sensor` updates belief state after estimator updates.
- `make_control` uses belief.mode to propose a patch, applies `safety_swarm::decide`, and emits `PolicyChosen` events when persisted.
- `BeliefUpdated` events are emitted alongside control decisions (~1-2Hz) and are part of the event envelope.

Determinism:
- Pathways are evaluated in a fixed Vec order.
- No concurrent evaluation in core.
- Event ordering is deterministic and included in replay hash.

See `crates/zenb-core/src/belief/mod.rs` and `crates/zenb-core/src/safety_swarm.rs` for implementation details.
