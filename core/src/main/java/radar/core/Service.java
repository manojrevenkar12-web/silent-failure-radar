package radar.core;

import java.util.List;

/**
 * One service in the catalog.
 *
 * @param tier 1 = customer-facing checkout path, 3 = internal tooling
 * @param calls the services this one calls (its upstream dependencies in the mesh)
 * @param businessMetric the metric that stops moving when the service fails silently, or null
 */
public record Service(String name, String team, int tier, List<String> calls, String businessMetric) {
    public Service {
        if (tier < 1 || tier > 3) throw new IllegalArgumentException("tier must be 1, 2 or 3");
        calls = List.copyOf(calls);
    }
}
