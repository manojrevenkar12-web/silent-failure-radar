# Working on this repository

## Fast feedback

- `./gradlew build` compiles with `-Xlint:all -Werror` and runs every Java test in about 30 s.
- `./gradlew :eval:evaluate :eval:gate :eval:readme` runs the three development seeds (under a minute), checks the
  gates and regenerates REPORT.md, the README block and `examples/`.
- `cd cli && gofmt -l . && go vet ./... && go test ./...` for the Go command; one test lints the generated monitors.
- `cd web && npm test && npm run typecheck && npm run lint` for the dashboard.

## Invariants

Each has a test or a CI step.

- **The truth stays in the world.** Designs get `Observed`: the catalog, the monitors that exist, deploys, past
  incidents and usage. Each service's code complexity, which changes carry a bug and when customers would have
  noticed stay in `World`; `World.play` answers what happens to a plan (`WorldTest.observedCarriesNoHiddenFacts`,
  `EvaluateTest.designsSeeOnlyWhatIsObserved`).
- **The world does not read the radar's settings.** `world/` loads `config/world.json` only
  (`WorldTest.theWorldReadsOnlyItsOwnConfig`).
- **Same luck for every design.** Every draw comes from a `SplittableRandom` keyed on (seed, what is drawn, which
  entity), so a deploy has the same bug and a customer the same patience under every design
  (`WorldTest.sameSeedSameWorldAndSameOutcome`).
- **Every alert reaches an owner.** A monitor needs `service:`, `team:`, `tier:` and `mode:` tags, the owning team's
  Slack handle, PagerDuty for tier 1 and a runbook link; `radar lint` refuses it otherwise, and CI lints `examples/`.
- **The radar being down never blocks a deploy.** `radar gate` falls back to a canary when the service cannot be
  reached (`TestGateFallsBackToCanaryWhenRadarIsDown`); it fails a build only when tests or a flag are missing.
- **Settings live in `config/`**: the world in `world.json`, the designs and the gate in `radar.json`, thresholds in
  `gates.json`, seeds in `seeds.json`. Each report carries the hash of all four.
- **Reports are generated, never edited.** CI regenerates REPORT.md, the README block and `examples/` and fails on a diff.

## Held-out seeds

- `./gradlew :eval:heldout` writes `reports/heldout.lock` and refuses to run while it exists
  (`EvaluateTest.heldOutRunsOnce`). CI checks the lock was committed once.
- Gates and the radar's settings were set on the development seeds before the held-out run. Never change them after it.

## Data

Everything is simulated: teams, services, deploys, incidents and costs. No real company's data.
