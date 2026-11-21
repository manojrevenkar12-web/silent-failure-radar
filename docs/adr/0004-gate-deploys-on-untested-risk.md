# 4. Gate deploys on untested risk, and fail open to a canary

## Context
Most incidents start with a change. Blocking every change is slow; letting every change go straight out ships the
risky ones to everyone at once.

## Decision
A logistic model trained on the history weeks scores each change from untested lines, size, untested share, blast
radius and recent incidents. Below `canaryAt` it ships; up to `testsAt` it goes to a 5% canary with automatic
rollback; above it, the change needs tests for its untested lines, a feature flag and a canary. The Go command
fails the build only for missing tests or a flag. If the radar cannot be reached, the pipeline rolls out as a canary
instead of stopping.

## Consequences
The report prices the gate in test hours a week next to the change failure rate and the rollouts caught in canary.
`no_gate` measures it. A wrong model sends too much to canary, which costs time, not outages.
