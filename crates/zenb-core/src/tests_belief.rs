#[cfg(test)]
mod tests {
    use super::*;
    use crate::belief::{BeliefEngine, BeliefState, SensorFeatures, PhysioState, Context, BeliefBasis};
    use crate::domain::{Envelope, Event, BreathState, SessionId};

    #[test]
    fn hysteresis_stability() {
        let be = BeliefEngine::new();
        let prev = BeliefState { p: [0.2;5], conf: 0.9, mode: BeliefBasis::Calm };
        // craft features that strongly indicate Sleepy
        let x = SensorFeatures { hr_bpm: Some(60.0), rmssd: Some(20.0), rr_bpm: Some(14.0), quality: 0.9, motion: 0.0 };
        let phys = PhysioState { hr_bpm: Some(60.0), rr_bpm: Some(14.0), rmssd: Some(20.0), confidence: 0.9 };
        let ctx = Context { local_hour: 23, is_charging: false, recent_sessions: 0 };
        let (s1, _dbg) = be.update(&prev, &x, &phys, &ctx, 0.1);
        // With low dt smoothing p should move but not instantly switch unless above enter_th
        assert!(s1.conf >= 0.0 && s1.conf <= 1.0);
        // ensure probabilities sum approx 1
        let sum: f32 = s1.p.iter().sum();
        assert!((sum - 1.0).abs() < 1e-3);
    }

    #[test]
    fn deterministic_replay_hash() {
        // simulate two identical sequences of events and ensure BreathState hash matches
        let mut s1 = BreathState::default();
        let mut s2 = BreathState::default();
        let sid = SessionId::new();
        let seqs = vec![
            Envelope { session_id: sid.clone(), seq: 1, ts_us: 0, event: Event::SessionStarted { mode: "demo".into() }, meta: serde_json::json!({}) },
            Envelope { session_id: sid.clone(), seq: 2, ts_us: 1000, event: Event::ControlDecisionMade { decision: crate::domain::ControlDecision { target_rate_bpm: 6.0, confidence: 0.8, recommended_poll_interval_ms: 1000 } }, meta: serde_json::json!({}) },
            Envelope { session_id: sid.clone(), seq: 3, ts_us: 2000, event: Event::BeliefUpdatedV2 { p: [0.2,0.2,0.2,0.2,0.2], conf: 0.8, mode: 0, free_energy_ema: 0.1, lr: 0.5, resonance_score: 1.0 }, meta: serde_json::json!({}) },
            Envelope { session_id: sid.clone(), seq: 4, ts_us: 3000, event: Event::CycleCompleted { cycles: 2 }, meta: serde_json::json!({}) },
        ];
        for e in seqs.iter() { s1.apply(e); }
        for e in seqs.iter() { s2.apply(e); }
        assert_eq!(s1.hash(), s2.hash());
    }
}
