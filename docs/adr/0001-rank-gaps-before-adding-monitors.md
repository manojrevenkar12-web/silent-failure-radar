# 1. Rank coverage gaps before adding monitors

## Context
The quick answer to "incidents are noticed by customers" is a monitor on everything. Every monitor also pages falsely
at some rate, and engineers who are paged too often slow down on the pages that matter. Teams have a limited number
of alerts they will keep looking at.

## Decision
Each team gets a monitor budget (`monitorBudgetPerTeam` in `config/radar.json`). Gaps are ranked by blast radius
(tier weight plus everyone upstream in the call graph), by how many past incidents in that service and mode were
noticed by chance rather than by a monitor, and by how badly static thresholds do on that mode. The budget is spent
from the top.

## Consequences
The `alert_everything` and `unranked` designs measure the decision: a static threshold everywhere triples the pages
and cuts little impact; the same budget spent at random cuts much less than spent by rank. A team whose services are
all low tier gets few new monitors, which is intended.
