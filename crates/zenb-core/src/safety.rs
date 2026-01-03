use std::time::Duration;

#[derive(Debug, Clone)]
pub struct SafetyConfig {
    pub rr_min: f32,
    pub rr_max: f32,
    pub max_rr_delta_per_min: f32,
    pub max_hold_us: u64,
    pub min_confidence: f32,
    pub min_update_interval_us: u64,
}

impl Default for SafetyConfig {
    fn default() -> Self {
        Self {
            rr_min: 4.0, // breaths per minute
            rr_max: 12.0,
            max_rr_delta_per_min: 2.0, // bpm per minute
            max_hold_us: 5 * 60_000_000, // 5 minutes
            min_confidence: 0.3,
            min_update_interval_us: 250_000, // 250ms
        }
    }
}

#[derive(Debug)]
pub struct SafetyEnvelope {
    pub cfg: SafetyConfig,
    last_patch_ts_us: Option<i64>,
    last_rate_bpm: Option<f32>,
}

impl SafetyEnvelope {
    pub fn new(cfg: SafetyConfig) -> Self {
        Self { cfg, last_patch_ts_us: None, last_rate_bpm: None }
    }

    /// Returns true if a proposed patch is allowed given confidence and rate limits.
    pub fn allow_patch(&mut self, ts_us: i64, proposed_bpm: f32, confidence: f32) -> bool {
        if confidence < self.cfg.min_confidence { return false; }
        if proposed_bpm < self.cfg.rr_min || proposed_bpm > self.cfg.rr_max { return false; }
        if let Some(last_ts) = self.last_patch_ts_us {
            let elapsed_us = (ts_us - last_ts) as u64;
            if elapsed_us < self.cfg.min_update_interval_us { return false; }
            // rate change per minute constraint
            if let Some(last_rate) = self.last_rate_bpm {
                let delta = (proposed_bpm - last_rate).abs();
                // allowed delta scaled to elapsed
                let allowed = self.cfg.max_rr_delta_per_min * (elapsed_us as f32 / 60_000_000f32);
                if delta > allowed + f32::EPSILON { return false; }
            }
        }
        true
    }

    pub fn record_patch(&mut self, ts_us: i64, new_rate: f32) {
        self.last_patch_ts_us = Some(ts_us);
        self.last_rate_bpm = Some(new_rate);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn safety_freeze_on_low_confidence() {
        let mut s = SafetyEnvelope::new(SafetyConfig::default());
        assert!(!s.allow_patch(0, 6.0, 0.1));
        assert!(s.allow_patch(0, 6.0, 0.9));
    }

    #[test]
    fn rate_limit_delta() {
        let mut s = SafetyEnvelope::new(SafetyConfig::default());
        assert!(s.allow_patch(0, 6.0, 0.9));
        s.record_patch(0, 6.0);
        // quick change should be denied
        assert!(!s.allow_patch(100_000, 9.0, 0.9));
    }
}
