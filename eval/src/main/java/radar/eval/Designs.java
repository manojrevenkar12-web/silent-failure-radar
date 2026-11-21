package radar.eval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import radar.core.Catalog;
import radar.core.Coverage;
import radar.core.DeployRisk;
import radar.core.Gap;
import radar.core.Mode;
import radar.core.Monitor;
import radar.core.MonitorKind;
import radar.core.Settings;
import radar.world.Observed;
import radar.world.World;
import tools.jackson.databind.JsonNode;

/**
 * Turns a design's settings into a {@link World.Plan} using only {@link Observed}: today's monitors, the incident
 * history, and CI facts for each deploy. The radar's own logic (gap ranking, monitors, risk model, gate) comes from
 * the core module, the same code the service runs.
 */
public final class Designs {
    private Designs() {}

    public static JsonNode spec(String name) {
        JsonNode designs = Settings.load("radar").get("designs");
        JsonNode d = designs.get(name);
        if (d == null) throw new IllegalArgumentException("unknown design " + name);
        if (!d.has("base")) return d;
        tools.jackson.databind.node.ObjectNode merged = ((tools.jackson.databind.node.ObjectNode) spec(d.get("base").asString())).deepCopy();
        d.properties().forEach(e -> {
            if (!e.getKey().equals("base")) merged.set(e.getKey(), e.getValue());
        });
        return merged;
    }

    public static List<String> names() {
        List<String> out = new ArrayList<>();
        Settings.load("radar").get("designs").propertyNames().forEach(out::add);
        return out;
    }

    /** The risk model, fitted on the history weeks: a deploy is positive if a recorded incident was traced to it. */
    public static DeployRisk fitRisk(Observed o) {
        JsonNode cfg = Settings.load("radar");
        Set<String> bad = new HashSet<>();
        for (Observed.PastIncident p : o.pastIncidents()) if (p.deployId() != null) bad.add(p.deployId());
        Map<String, Integer> recent = new HashMap<>();
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        List<Observed.PastIncident> past = new ArrayList<>(o.pastIncidents());
        int pi = 0;
        past.sort(java.util.Comparator.comparingLong(Observed.PastIncident::start));
        for (World.Deploy d : o.historyDeploys()) {
            while (pi < past.size() && past.get(pi).start() < d.minute()) recent.merge(past.get(pi++).service(), 1, Integer::sum);
            xs.add(DeployRisk.features(d.change(), o.catalog().blastRadius(d.change().service()), recent.getOrDefault(d.change().service(), 0)));
            ys.add(bad.contains(d.change().id()) ? 1 : 0);
        }
        return DeployRisk.fit(xs, ys, cfg.get("riskModel").get("l2").asDouble(), cfg.get("riskModel").get("iterations").asInt());
    }

    public static World.Plan plan(String name, Observed o, DeployRisk risk, long seed) {
        JsonNode d = spec(name);
        JsonNode cfg = Settings.load("radar");
        Catalog cat = o.catalog();
        List<Monitor> monitors = new ArrayList<>(o.existingMonitors());
        String how = d.get("monitors").asString();
        if (how.equals("everything")) {
            for (var s : cat.services()) {
                for (Mode m : Mode.values()) {
                    boolean have = monitors.stream().anyMatch(x -> x.service().equals(s.name()) && x.mode() == m);
                    if (!have) monitors.add(new Monitor(s.name(), m, MonitorKind.STATIC_THRESHOLD, true));
                }
            }
        } else if (how.equals("ranked") || how.equals("random")) {
            List<Coverage.IncidentRecord> hist = o.pastIncidents().stream().map(p -> new Coverage.IncidentRecord(p.service(), p.mode(), p.caughtByMonitor())).toList();
            List<Gap> gaps = new ArrayList<>(Coverage.gaps(cat, monitors, hist));
            if (how.equals("random")) java.util.Collections.shuffle(gaps, new java.util.Random(seed * 31 + 7));
            int budget = cfg.get("monitorBudgetPerTeam").asInt();
            Map<String, Integer> used = new HashMap<>();
            boolean staticOnly = d.has("kind") && d.get("kind").asString().equals("static");
            for (Gap g : gaps) {
                String team = cat.service(g.service()).team();
                if (used.getOrDefault(team, 0) >= budget) continue;
                MonitorKind kind = staticOnly && g.mode() != Mode.SILENT_DROP ? MonitorKind.STATIC_THRESHOLD : g.fix();
                if (staticOnly && g.mode() == Mode.SILENT_DROP) continue;
                // a new monitor replaces a static threshold on the same service and mode
                monitors.removeIf(x -> x.service().equals(g.service()) && x.mode() == g.mode());
                monitors.add(new Monitor(g.service(), g.mode(), kind, true));
                used.merge(team, 1, Integer::sum);
            }
        }
        Map<String, World.Gating> gates = new HashMap<>();
        if (d.has("gate") && d.get("gate").asBoolean()) {
            JsonNode gc = cfg.get("gate");
            Map<String, Integer> recent = new HashMap<>();
            for (Observed.PastIncident p : o.pastIncidents()) {
                if (p.start() >= (long) (o.historyWeeks() - cfg.get("recentIncidentWeeks").asInt()) * World.WEEK) recent.merge(p.service(), 1, Integer::sum);
            }
            for (World.Deploy dep : o.liveDeploys()) {
                var c = dep.change();
                double r = risk.score(DeployRisk.features(c, cat.blastRadius(c.service()), recent.getOrDefault(c.service(), 0)));
                DeployRisk.Gate g = DeployRisk.gate(r, gc.get("canaryAt").asDouble(), gc.get("testsAt").asDouble());
                if (g == DeployRisk.Gate.SHIP) continue;
                int added = 0;
                if (g == DeployRisk.Gate.TESTS_FLAG_AND_CANARY) {
                    int target = (int) Math.ceil(c.changedLines() * gc.get("testedShareAfterGate").asDouble());
                    added = Math.max(0, target - c.testedLines());
                }
                gates.put(c.id(), new World.Gating(added, c.flagged() || g == DeployRisk.Gate.TESTS_FLAG_AND_CANARY, true));
            }
        }
        boolean track = d.has("trackActions") && d.get("trackActions").asBoolean();
        boolean brief = d.has("brief") && d.get("brief").asBoolean();
        return new World.Plan(monitors, gates, track, brief);
    }
}
