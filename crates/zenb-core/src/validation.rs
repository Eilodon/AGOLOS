/// Input validation layer for sensor data and control decisions.
#[derive(Debug, Clone)]
pub enum SensorError {
    InvalidData(String),
    BurstDetected,
}

/// Validate raw sensor input before ingestion.
pub fn validate_sensor(features: &[f32]) -> Result<(), SensorError> {
    if features.iter().any(|f| f.is_nan() || f.is_infinite()) {
        return Err(SensorError::InvalidData("NaN/Inf detected".into()));
    }
    // TODO: add range checks, burst detection, etc.
    Ok(())
}

#[derive(Debug, Clone)]
pub struct CircuitBreaker {
    failure_count: u32,
    last_failure_us: Option<i64>,
    threshold: u32,
    cooldown_us: i64,
}

impl Default for CircuitBreaker {
    fn default() -> Self {
        Self {
            failure_count: 0,
            last_failure_us: None,
            threshold: 5,
            cooldown_us: 60_000_000, // 1 minute
        }
    }
}

impl CircuitBreaker {
    pub fn trip(&mut self, now_us: i64) {
        self.failure_count += 1;
        self.last_failure_us = Some(now_us);
    }

    pub fn allow(&self, now_us: i64) -> bool {
        if let Some(last) = self.last_failure_us {
            if self.failure_count >= self.threshold && (now_us - last) < self.cooldown_us {
                return false;
            }
        }
        true
    }

    pub fn reset(&mut self) {
        self.failure_count = 0;
        self.last_failure_us = None;
    }
}
