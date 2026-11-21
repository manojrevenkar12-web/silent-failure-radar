package radar.world;

import java.util.List;
import radar.core.Catalog;
import radar.core.CostLens;
import radar.core.Mode;
import radar.core.Monitor;

/**
 * Everything a design may see: the catalog and mesh graph, today's monitors, CI facts for every deploy, the incidents
 * of the history weeks as the incident tracker recorded them (only those that someone noticed), and resource usage.
 */
public record Observed(Catalog catalog, List<Monitor> existingMonitors, List<World.Deploy> historyDeploys, List<World.Deploy> liveDeploys,
                       List<PastIncident> pastIncidents, List<CostLens.Usage> usage, int historyWeeks, int liveWeeks, double cpuEur, double memEur) {

    /** An incident from the tracker. {@code deployId} is set when the post-incident review traced it to a deploy. */
    public record PastIncident(String id, String service, Mode mode, long start, boolean caughtByMonitor, String deployId, String signal) {}
}
