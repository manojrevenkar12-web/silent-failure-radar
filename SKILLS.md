# Repeatable tasks

## Add a failure mode
1. Add it to `Mode`, to `modeShare` and `existingCoverage` in `config/world.json`, and say in `World` which monitor
   kinds see it and how customers notice it.
2. Give it a query in `Monitors.datadog` and a label; add a lint rule in `cli/lint.go` if it needs one (silent drops
   must alert on no data).
3. Dev run, gate, readme. The README's top-gaps table shows whether the ranking now picks it.

## Change the deploy-risk gate
Change `gate` in `config/radar.json` on development seeds only. The report shows the trade: test hours a week,
change failure rate and bad rollouts caught in the canary. Never after the held-out run.

## Apply the generated monitors to a Datadog account
1. `./gradlew :eval:readme` (or `GET /api/monitors/{service}/{mode}?format=terraform` for one service).
2. `radar lint examples/datadog/*.json` must pass.
3. Copy `examples/monitors.tf` into the Terraform workspace that holds the Datadog provider, `terraform plan`, and
   review the routing with each owning team before `apply`.

## Wire the gate into a pipeline
Run the service, then in the deploy job:
`radar gate -url $RADAR_URL -id $GIT_SHA -service $SERVICE -changed $LINES -tested $COVERED_LINES [-flagged]`.
It prints `ROLLOUT=full` or `ROLLOUT=canary` for the rollout step and exits 1 when tests or a flag are missing.

## Point Datadog alerts at the brief
Add a webhook integration whose URL is `POST /webhooks/datadog` with payload
`{"service": "{{service.name}}", "mode": "{{mode.name}}", "title": "$EVENT_TITLE", "alertId": "$ALERT_ID"}` (the
radar's monitors carry `service:` and `mode:` tags), and add the webhook handle to the monitor message.

## Run the held-out seeds (once)
`./gradlew :eval:heldout :eval:gate :eval:readme`, then commit the lock, the report and the README in one commit.

## Refresh the dashboard screenshots
`cd web && npm run build`, then `python3 scripts/screenshots.py`.
