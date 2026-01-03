# Causal Reasoning Layer - Implementation Summary

## Overview
The Causal Reasoning Layer enables ZenB to understand **why** things happen, moving beyond reactive responses to causal understanding. This groundwork prepares for the NOTEARS algorithm implementation.

## Architecture

### Core Components

#### 1. `Variable` Enum (`causal.rs`)
Represents all causal nodes in the system:
- `NotificationPressure` - Digital stressor from notifications
- `HeartRate` - Physiological arousal indicator
- `HeartRateVariability` - Stress resilience measure
- `Location` - Environmental context
- `TimeOfDay` - Circadian influence
- `UserAction` - System interventions
- `InteractionIntensity` - Digital engagement level
- `RespiratoryRate` - Breath pattern
- `NoiseLevel` - Environmental stressor

#### 2. `CausalGraph` Struct
Directed Acyclic Graph (DAG) using adjacency matrix representation:
- **Storage**: `weights[cause][effect]` matrix (9x9)
- **Range**: Edge weights from -1.0 (strong negative) to 1.0 (strong positive)
- **Serialization**: Full `serde` support for EventStore persistence

**Key Methods**:
```rust
// Get causal effect strength
pub fn get_effect(&self, cause: Variable, target: Variable) -> f32

// Set causal relationship
pub fn set_effect(&mut self, cause: Variable, target: Variable, weight: f32)

// Predict future state from action
pub fn predict_outcome(&self, current_state: &BeliefState, proposed_action: &ActionPolicy) -> PredictedState

// Verify DAG property
pub fn is_acyclic(&self) -> bool
```

**Domain Knowledge Priors**:
The graph initializes with conservative priors:
- NotificationPressure → HeartRate: +0.3 (stress response)
- NotificationPressure → HRV: -0.2 (reduced resilience)
- RespiratoryRate → HeartRate: -0.3 (RSA effect)
- RespiratoryRate → HRV: +0.4 (breath coherence)
- UserAction → RespiratoryRate: +0.5 (breath guidance)

#### 3. `CausalBuffer` Struct
Sliding window buffer (default: 1000 observations):
- **Storage**: Circular buffer of `ObservationSnapshot`
- **Purpose**: Batch learning data for NOTEARS algorithm
- **Efficiency**: O(1) push, automatic oldest-entry eviction

**Key Methods**:
```rust
// Add observation to buffer
pub fn push(&mut self, snapshot: ObservationSnapshot)

// Get recent N observations
pub fn get_recent(&self, n: usize) -> Vec<&ObservationSnapshot>

// Extract data matrix for learning
pub fn to_data_matrix(&self) -> Vec<Vec<f32>>
```

#### 4. Supporting Types

**`ActionPolicy`**: Represents proposed interventions
```rust
pub struct ActionPolicy {
    pub action_type: ActionType,  // BreathGuidance, NotificationBlock, etc.
    pub intensity: f32,            // [0, 1]
}
```

**`PredictedState`**: Outcome prediction
```rust
pub struct PredictedState {
    pub predicted_hr: f32,
    pub predicted_hrv: f32,
    pub predicted_rr: f32,
    pub confidence: f32,
}
```

**`ObservationSnapshot`**: Timestamped observation + action + belief
```rust
pub struct ObservationSnapshot {
    pub timestamp_us: i64,
    pub observation: Observation,
    pub action: Option<ActionPolicy>,
    pub belief_state: Option<BeliefState>,
}
```

## Engine Integration

### Modified `Engine` Struct
Added three new fields:
```rust
pub struct Engine {
    // ... existing fields ...
    pub causal_graph: CausalGraph,
    pub causal_buffer: CausalBuffer,
    pub last_observation: Option<Observation>,
}
```

### Initialization
```rust
// In Engine::new_with_config()
causal_graph: CausalGraph::with_priors(),  // Starts with domain knowledge
causal_buffer: CausalBuffer::default_capacity(),  // 1000 observation capacity
last_observation: None,
```

### Runtime Integration

#### Observation Ingestion
```rust
// New method to ingest full observations
pub fn ingest_observation(&mut self, observation: Observation) {
    self.last_observation = Some(observation);
}
```

#### Automatic Buffering in `tick()`
```rust
pub fn tick(&mut self, dt_us: u64) -> u64 {
    let cycles = self.breath.tick(dt_us);
    
    // Automatically push observations to causal buffer
    if let Some(ref obs) = self.last_observation {
        let snapshot = ObservationSnapshot {
            timestamp_us: obs.timestamp_us,
            observation: obs.clone(),
            action: None,
            belief_state: Some(/* mapped from belief_state */),
        };
        self.causal_buffer.push(snapshot);
    }
    
    cycles
}
```

## Usage Examples

### Basic Usage
```rust
use zenb_core::{Engine, Observation, BioMetrics, DigitalContext};

let mut engine = Engine::new(6.0);

// Create observation
let obs = Observation {
    timestamp_us: 1000000,
    bio_metrics: Some(BioMetrics {
        hr_bpm: Some(75.0),
        hrv_rmssd: Some(45.0),
        respiratory_rate: Some(6.0),
    }),
    digital_context: Some(DigitalContext {
        notification_pressure: Some(0.8),
        interaction_intensity: Some(0.6),
        active_app_category: None,
    }),
    environmental_context: None,
};

// Ingest observation
engine.ingest_observation(obs);

// Tick engine (automatically buffers observation)
engine.tick(1000000);

// Check buffer status
println!("Buffer size: {}", engine.causal_buffer.len());
```

### Causal Prediction
```rust
use zenb_core::{ActionPolicy, ActionType, BeliefState};

// Current belief state
let belief_state = engine.belief_state;

// Propose breath guidance action
let action = ActionPolicy {
    action_type: ActionType::BreathGuidance,
    intensity: 0.8,
};

// Predict outcome
let prediction = engine.causal_graph.predict_outcome(&belief_state, &action);

println!("Predicted HR: {:.1} BPM", prediction.predicted_hr);
println!("Predicted HRV: {:.1} ms", prediction.predicted_hrv);
println!("Confidence: {:.2}", prediction.confidence);
```

### Querying Causal Relationships
```rust
use zenb_core::{Variable, CausalGraph};

let graph = CausalGraph::with_priors();

// Get effect strength
let effect = graph.get_effect(
    Variable::NotificationPressure,
    Variable::HeartRate
);
println!("Notification → HR effect: {:.2}", effect);

// Get all causes of high heart rate
let causes = graph.get_causes(Variable::HeartRate);
for (cause, strength) in causes {
    println!("{:?} → HR: {:.2}", cause, strength);
}

// Verify DAG property
assert!(graph.is_acyclic());
```

### Data Extraction for Learning
```rust
// Extract data matrix for NOTEARS algorithm
let data_matrix = engine.causal_buffer.to_data_matrix();

println!("Data shape: {} x {}", data_matrix.len(), Variable::COUNT);

// Each row is a time point, each column is a variable
// Ready for causal discovery algorithms
```

## Design Decisions

### 1. Adjacency Matrix vs. Edge List
**Choice**: Adjacency matrix
**Rationale**: 
- O(1) edge weight lookup
- Dense graph expected (many causal relationships)
- Simple serialization
- Fixed size (9x9 = 81 floats = 324 bytes)

### 2. No Heavy Math Crates
**Constraint**: Avoided `ndarray`, `nalgebra`
**Rationale**:
- Keep dependencies lightweight
- Standard `Vec` sufficient for current needs
- Easy to upgrade later when implementing NOTEARS

### 3. Circular Buffer
**Choice**: Fixed-size circular buffer
**Rationale**:
- Bounded memory usage
- O(1) push operation
- Automatic old data eviction
- Suitable for online learning

### 4. Domain Priors
**Choice**: Initialize with conservative causal priors
**Rationale**:
- Faster convergence than zero initialization
- Encodes expert knowledge
- Can be overridden by learning

## Testing

Comprehensive test suite in `causal.rs`:
- `test_variable_indexing` - Variable enum consistency
- `test_causal_graph_creation` - Graph initialization
- `test_causal_graph_set_get` - Edge manipulation
- `test_causal_graph_acyclic` - DAG property verification
- `test_causal_buffer_push` - Buffer operations
- `test_causal_buffer_circular` - Circular buffer behavior
- `test_predict_outcome` - Prediction functionality

## Next Steps (NOTEARS Implementation)

### Phase 2: Learning Algorithm
1. **Add `ndarray` dependency** for matrix operations
2. **Implement NOTEARS optimizer**:
   - Continuous optimization with acyclicity constraint
   - L1 regularization for sparsity
   - Augmented Lagrangian method
3. **Batch learning method**:
   ```rust
   pub fn learn_structure(&mut self, data: &CausalBuffer) -> CausalGraph
   ```

### Phase 3: Online Learning
1. **Incremental updates** without full retraining
2. **Forgetting factor** for non-stationary environments
3. **Uncertainty quantification** for predictions

### Phase 4: Intervention Planning
1. **Counterfactual reasoning**: "What if we had blocked notifications?"
2. **Optimal intervention selection** using causal graph
3. **Multi-step lookahead** planning

## Integration Points

### EventStore Serialization
The `CausalGraph` is fully serializable:
```rust
// Save to EventStore
let graph_json = serde_json::to_string(&engine.causal_graph)?;

// Load from EventStore
let graph: CausalGraph = serde_json::from_str(&graph_json)?;
```

### Belief Engine Connection
The causal layer connects to existing belief state:
- Maps `BeliefState` probabilities to causal variables
- Provides predictions for policy selection
- Enables causal explanations for interventions

### Safety Layer Integration
Future: Use causal graph to predict safety violations before they occur.

## Performance Characteristics

- **Graph lookup**: O(1) - direct array indexing
- **Buffer push**: O(1) - circular buffer
- **Prediction**: O(V²) where V=9 - single matrix pass
- **Memory**: ~5KB for graph + buffer metadata + 1000 observations (~500KB)

## Constraints Satisfied

✅ **No heavy math crates** - Uses only `Vec` and arrays  
✅ **Serialization** - Full `serde` support  
✅ **Integration** - Connected to `Observation` and `BeliefState`  
✅ **DAG structure** - Acyclicity verification included  
✅ **Lightweight** - Minimal dependencies, efficient storage  

## Files Modified

1. **Created**: `crates/zenb-core/src/causal.rs` (600+ lines)
2. **Modified**: `crates/zenb-core/src/engine.rs` (added 3 fields, 2 methods)
3. **Modified**: `crates/zenb-core/src/lib.rs` (exported causal module)

## Summary

The Causal Reasoning Layer is now fully implemented and integrated into ZenB. The system can:
- Represent causal relationships as a DAG
- Store observations for batch learning
- Predict intervention outcomes
- Maintain domain knowledge priors

The groundwork is complete for implementing the NOTEARS algorithm in the next phase.
