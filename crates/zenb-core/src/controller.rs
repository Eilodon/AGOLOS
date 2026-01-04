use crate::belief::Context;
use crate::estimator::Estimate;

#[derive(Debug, Clone)]
pub struct ControllerConfig {
    pub decision_epsilon_bpm: f32,
    pub min_decision_interval_us: i64,
}

impl Default for ControllerConfig {
    fn default() -> Self {
        Self {
            decision_epsilon_bpm: 0.1,
            min_decision_interval_us: 250_000,
        }
    }
}

#[derive(Debug, Clone)]
pub struct AdaptiveController {
    pub cfg: ControllerConfig,
    pub(crate) last_decision_ts_us: Option<i64>,
    pub(crate) last_decision_bpm: Option<f32>,
}

impl AdaptiveController {
    pub fn new(cfg: ControllerConfig) -> Self {
        Self {
            cfg,
            last_decision_ts_us: None,
            last_decision_bpm: None,
        }
    }

    /// Decide a target rate based on estimate and previous decision; returns (rate_bpm, changed)
    pub fn decide(&mut self, est: &Estimate, ts_us: i64) -> (f32, bool) {
        // If no RR estimate, fallback to last decision or default 6.0
        let base = est.rr_bpm.or(self.last_decision_bpm).unwrap_or(6.0);
        let target = base.clamp(4.0, 12.0);
        let changed = match self.last_decision_bpm {
            Some(prev) => (prev - target).abs() > self.cfg.decision_epsilon_bpm,
            None => true,
        } && match self.last_decision_ts_us {
            Some(last_ts) => (ts_us - last_ts) >= self.cfg.min_decision_interval_us,
            None => true,
        };
        if changed {
            self.last_decision_bpm = Some(target);
            self.last_decision_ts_us = Some(ts_us);
        }
        (target, changed)
    }
}

/// Compute adaptive polling interval based on Free Energy (entropy) and confidence.
///
/// Implements "Elastic Spacetime":
/// - High Free Energy + low belief confidence => faster polling (time contracts)
/// - Low Free Energy + high belief confidence => slower polling (time dilates)
///
/// # Arguments
/// * `free_energy_ema` - Current free energy EMA (higher => higher entropy/urgency)
/// * `belief_confidence` - Belief confidence in [0, 1]
/// * `action_taken` - Whether a control action was just taken
/// * `ctx` - Runtime context (charging state, etc.)
///
/// # Returns
/// Recommended polling interval in milliseconds
pub fn compute_poll_interval(
    free_energy_ema: f32,
    belief_confidence: f32,
    action_taken: bool,
    ctx: &Context,
) -> u64 {
    // Immediate feedback loop if an action was taken
    if action_taken {
        return 200;
    }

    let base_interval_ms = 5000.0f32;
    let urgency = free_energy_ema.max(0.0) * (1.0 - belief_confidence.clamp(0.0, 1.0));
    let target_ms = base_interval_ms / (1.0 + urgency * 10.0);
    let mut clamped_ms = target_ms.clamp(200.0, 30000.0);

    // Context modifier: if charging, we can afford more compute (poll faster)
    if ctx.is_charging {
        clamped_ms *= 0.8;
    }

    clamped_ms = clamped_ms.clamp(200.0, 30000.0);
    clamped_ms.round() as u64
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::estimator::Estimator;

    #[test]
    fn controller_basic_change() {
        let mut c = AdaptiveController::new(ControllerConfig::default());
        let mut e = Estimator::default();
        let est = e.ingest(&[60.0, 30.0, 6.0], 0);
        let (r, changed) = c.decide(&est, 0);
        assert!(changed);
        assert!((r - 6.0).abs() < 1e-3);
    }
}
