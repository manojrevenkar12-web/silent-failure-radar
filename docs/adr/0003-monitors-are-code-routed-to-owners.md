# 3. Monitors are code, routed to the owning team

## Context
An alert that lands in a shared channel waits for someone to notice whose it is. Monitors edited by hand in the UI
drift and cannot be reviewed.

## Decision
Every monitor is generated from the catalog: tags for service, team, tier and failure mode; the owning team's Slack
handle; PagerDuty for tier 1; a runbook link. It is emitted as Datadog API JSON and as a Terraform
`datadog_monitor` resource, and `radar lint` rejects any monitor that misses one of these before it is applied.

## Consequences
Ownership has to be right in the catalog, which is where it should be fixed anyway. The world models unrouted
alerts as a delay before the right person acknowledges.
