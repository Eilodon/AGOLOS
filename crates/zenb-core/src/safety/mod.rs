//! Safety Monitor module
//!
//! LTL runtime verification

pub mod monitor;

pub use monitor::{RuntimeState, SafetyMonitor, SafetyProperty, SafetyViolation, Severity};
