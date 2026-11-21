package radar.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Monitors as code: the Datadog monitor definition for a gap, routed to the owning team, as the JSON body the
 * Datadog API takes and as a Terraform {@code datadog_monitor} resource.
 *
 * Burn-rate monitors use the fast-burn threshold from the SRE workbook: page when the last hour burns the error
 * budget 14.4 times faster than allowed (2% of a 30-day budget in one hour). A single window keeps the monitor one
 * Datadog query; a short-window confirmation would need a composite monitor and is left out.
 */
public final class Monitors {
    private Monitors() {}

    public static Map<String, Object> datadog(Catalog catalog, String service, Mode mode, double sloTarget) {
        Service s = catalog.service(service);
        Team t = catalog.owner(service);
        MonitorKind kind = Coverage.bestKind(mode);
        double budget = 1 - sloTarget;
        String svc = "service:" + service;
        String query = switch (mode) {
            case ERROR_SPIKE, DEPENDENCY -> String.format(Locale.ROOT,
                    "(sum(last_1h):sum:trace.http.request.errors{%s}.as_count() / sum:trace.http.request.hits{%s}.as_count()) > %s",
                    svc, svc, fmt(14.4 * budget));
            case LATENCY -> String.format(Locale.ROOT,
                    "percentile(last_15m):p95:trace.http.request.duration{%s} > %s", svc, fmt(latencyBudgetSeconds(s.tier())));
            case SATURATION -> String.format(Locale.ROOT,
                    "avg(last_10m):avg:kubernetes.cpu.usage.total{%s} / avg:kubernetes.cpu.limits{%s} > 0.9", svc, svc);
            case SILENT_DROP -> String.format(Locale.ROOT,
                    "avg(last_30m):anomalies(sum:%s{%s}.as_count(), 'agile', 3, direction='below') >= 1", s.businessMetric(), svc);
        };
        String handles = s.tier() == 1 ? "@" + t.pagerduty() + " @" + t.slack() : "@" + t.slack();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", String.format(Locale.ROOT, "[%s] %s %s", service, mode.name().toLowerCase(Locale.ROOT).replace('_', ' '), label(mode)));
        m.put("type", "query alert");
        m.put("query", query);
        m.put("message", String.format(Locale.ROOT,
                "{{#is_alert}}%s %s on %s. Runbook: https://runbooks.internal/%s/%s %s{{/is_alert}}",
                label(mode), mode.name().toLowerCase(Locale.ROOT).replace('_', ' '), service, service, mode.name().toLowerCase(Locale.ROOT), handles));
        List<String> tags = new ArrayList<>(List.of(svc, "team:" + t.name(), "tier:" + s.tier(), "mode:" + mode.name().toLowerCase(Locale.ROOT), "managed-by:silent-failure-radar"));
        m.put("tags", tags);
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("notify_no_data", mode == Mode.SILENT_DROP);
        options.put("renotify_interval", s.tier() == 1 ? 30 : 0);
        options.put("require_full_window", false);
        if (kind == MonitorKind.BURN_RATE && (mode == Mode.ERROR_SPIKE || mode == Mode.DEPENDENCY)) {
            options.put("thresholds", Map.of("critical", round(14.4 * budget), "warning", round(6 * budget)));
        }
        m.put("options", options);
        m.put("priority", s.tier());
        return m;
    }

    /** The same monitor as a Terraform resource for the Datadog provider. */
    public static String terraform(Map<String, Object> monitor) {
        String id = ((String) monitor.get("name")).replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", "").toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        b.append("resource \"datadog_monitor\" \"").append(id).append("\" {\n");
        b.append("  name     = ").append(quote((String) monitor.get("name"))).append('\n');
        b.append("  type     = ").append(quote((String) monitor.get("type"))).append('\n');
        b.append("  query    = ").append(quote((String) monitor.get("query"))).append('\n');
        b.append("  message  = ").append(quote((String) monitor.get("message"))).append('\n');
        b.append("  priority = ").append(monitor.get("priority")).append('\n');
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) monitor.get("tags");
        b.append("  tags     = [").append(String.join(", ", tags.stream().map(Monitors::quote).toList())).append("]\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) monitor.get("options");
        b.append("  notify_no_data       = ").append(o.get("notify_no_data")).append('\n');
        b.append("  renotify_interval    = ").append(o.get("renotify_interval")).append('\n');
        b.append("  require_full_window  = ").append(o.get("require_full_window")).append('\n');
        if (o.containsKey("thresholds")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> th = (Map<String, Object>) o.get("thresholds");
            b.append("  monitor_thresholds {\n    critical = ").append(th.get("critical")).append("\n    warning  = ").append(th.get("warning")).append("\n  }\n");
        }
        b.append("}\n");
        return b.toString();
    }

    static double latencyBudgetSeconds(int tier) {
        return tier == 1 ? 0.8 : tier == 2 ? 1.5 : 3.0;
    }

    /** What the monitor watches, in the words the on-call engineer sees. */
    public static String label(Mode mode) {
        return switch (mode) {
            case ERROR_SPIKE, DEPENDENCY -> "SLO burn rate";
            case LATENCY -> "p95 over latency budget";
            case SATURATION -> "CPU above 90% of limit";
            case SILENT_DROP -> "business-metric anomaly";
        };
    }

    private static double round(double x) {
        return Math.round(x * 1e6) / 1e6;
    }

    private static String fmt(double x) {
        return String.format(Locale.ROOT, "%.6f", round(x)).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("${", "$${") + "\"";
    }
}
