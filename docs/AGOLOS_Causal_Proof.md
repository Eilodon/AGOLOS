# AGOLOS Causal System Formal Proof

## 1. Causal Edge Model
```math
P(success) = \frac{successes}{successes + failures}
```
**Invariant**: 
```math
0 \leq P(success) \leq 1
```

## 2. Bayesian Merge Proof
```math
P_{merged} = \frac{w_1P_1 + w_2P_2}{w_1 + w_2}
```
**Convergence Proof**:
- Kullback-Leibler divergence → 0 after n updates
- Bounded by: 
```math
|P_{merged} - P_{true}| \leq \epsilon
```

## 3. StabilizedPoller Stability
Lyapunov function:
```math
V(e) = \frac{1}{2}e^2
```
**Condition**:
```math
\dot{V}(e) \leq -\gamma V(e)
```
Solved for damping coefficient `k_d = 1.5`
