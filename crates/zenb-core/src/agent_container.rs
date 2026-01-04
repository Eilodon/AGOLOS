use std::sync::{Arc, Mutex};
use crate::belief::{AgentStrategy, SensorFeatures, PhysioState, Context};

/// Versioned, resource-guarded container for cognitive agents.
#[derive(Debug, Clone)]
pub struct AgentContainer {
    pub inner: Arc<Mutex<AgentStrategy>>,
    pub version: String, // Git commit hash or build ID
    pub resource_limits: ResourceQuota,
}

#[derive(Debug, Clone)]
pub struct ResourceQuota {
    pub max_cpu_ms_per_tick: u64,
    pub max_memory_mb: usize,
}

impl Default for ResourceQuota {
    fn default() -> Self {
        Self {
            max_cpu_ms_per_tick: 5,
            max_memory_mb: 10,
        }
    }
}

impl AgentContainer {
    pub fn new(agent: AgentStrategy, version: String) -> Self {
        Self {
            inner: Arc::new(Mutex::new(agent)),
            version,
            resource_limits: ResourceQuota::default(),
        }
    }

    pub fn evaluate(&self, x: &SensorFeatures, phys: &PhysioState, ctx: &Context) -> f32 {
        // TODO: enforce resource limits and kill-switch
        self.inner.lock().unwrap().eval(x, phys, ctx).confidence
    }
}
