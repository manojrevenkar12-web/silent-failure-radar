# 2. Burn rates and anomaly monitors over static thresholds

## Context
Static thresholds miss slow and partial failures and page on harmless spikes. Silent failures (a payment provider
quietly declining, bookings dropping to zero for one market) raise no error at all.

## Decision
Error and dependency failures get an SLO burn-rate monitor at 14.4 times the error budget over one hour, the
fast-burn threshold from the SRE workbook. Latency gets a p95 budget by tier; saturation a CPU-to-limit ratio.
Silent drops get a Datadog anomaly monitor on the service's business metric with `notify_no_data`, so a metric that
stops reporting also alerts. One window per monitor keeps each one a single query; a short confirming window would
need a composite monitor.

## Consequences
`static_only` measures the decision on the same budget. Services without a business metric cannot get a
silent-drop monitor; the gap stays on the list and the report counts it.
