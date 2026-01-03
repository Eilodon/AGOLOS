# Changelog

## v2.0 Gold — Last Mile (2026-01-02)

- Add `BreathMode` (Dynamic/Fixed) to `zenb-core` BreathEngine
- Persist `ControlDecisionDenied` events with reason strings (downsampled)
- Debounce `Context` updates in `zenb-uniffi` runtime
- `ResourceGuard` conservative behavior when unplugged
- Add tests including a 10-minute session integration test
- Add CI workflow to run fmt, clippy, tests, and a uniffi smoke generation step
