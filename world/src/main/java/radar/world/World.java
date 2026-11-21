package radar.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import radar.core.Catalog;
import radar.core.Change;
import radar.core.CostLens;
import radar.core.Coverage;
import radar.core.Fingerprint;
import radar.core.Mode;
import radar.core.Monitor;
import radar.core.MonitorKind;
import radar.core.Service;
import radar.core.Settings;
import radar.core.Team;
import tools.jackson.databind.JsonNode;

/**
 * The simulated engineering organisation: teams, services and the mesh graph between them, the monitors they have
 * today, and every deploy over the history and live weeks.
 *
 * Hidden here: how bug-prone each service's code is, which deploy carries a bug, how each failure shows itself, how
 * long customers take to complain, how long a person takes to find the cause, and whether follow-up actions really
 * get done. Designs see {@link Observed} and hand back a {@link Plan}; {@link #play} applies the plan to the same
 * luck and returns what happened. Only the evaluator reads the outcomes against the truth.
 */
public final class World {
    public static final long WEEK = 7 * 24 * 60;
    public static final List<String> SEVERITIES = List.of("low", "medium", "high");

    public final long seed;
    private final JsonNode cfg;
    private final Rand rand;
    private final Catalog catalog;
    private final Map<String, Double> complexity = new HashMap<>();
    private final List<Monitor> existing = new ArrayList<>();
    private final List<Deploy> deploys = new ArrayList<>();
    private final List<CostLens.Usage> usage = new ArrayList<>();
    private final int historyWeeks, liveWeeks;
    private Observed observed;

    /** A deploy and when it went out. */
    public record Deploy(Change change, int week, long minute) {}

    /** What a design decided for one deploy. */
    public record Gating(int addedTestedLines, boolean flag, boolean canary) {}

    /** Everything a design decides. */
    public record Plan(List<Monitor> monitors, Map<String, Gating> gates, boolean trackActions, boolean brief) {}

    /** One incident and how it went. */
    public record Incident(String id, String service, String team, Mode mode, String severity, long start, long detected, long resolved,
                           boolean byMonitor, String deployId, boolean recurrence, boolean linkedToPrior, boolean caughtInCanary,
                           double impact, String signal) {
        public long mttd() {
            return detected - start;
        }

        public long mttr() {
            return resolved - start;
        }

        public int week() {
            return (int) (start / WEEK);
        }
    }

    /** What happened under a plan. */
    public record Outcome(List<Incident> incidents, Map<String, int[]> truePages, Map<String, int[]> falsePages, double testHours, int weeks, int firstWeek) {}

    public static World simulate(long seed) {
        return new World(seed, Settings.load("world"));
    }

    public World(long seed, JsonNode cfg) {
        this.seed = seed;
        this.cfg = cfg;
        this.rand = new Rand(seed);
        this.historyWeeks = cfg.get("historyWeeks").asInt();
        this.liveWeeks = cfg.get("liveWeeks").asInt();
        int nTeams = cfg.get("teams").asInt(), nServices = cfg.get("services").asInt();
        List<Team> teams = new ArrayList<>();
        for (int t = 0; t < nTeams; t++) {
            String name = "team-" + (char) ('a' + t);
            teams.add(new Team(name, "slack-" + name, "pagerduty-" + name));
        }
        double[] tierShare = doubles(cfg.get("tierShare"));
        List<Service> services = new ArrayList<>();
        String[] metrics = {"bookings.confirmed", "payments.captured", "tickets.issued", "emails.sent", "reviews.published", "payouts.made", "searches.served"};
        // services are created callee-first, so each calls only services created before it: the graph has no cycles
        for (int i = 0; i < nServices; i++) {
            double u = rand.u("tier", i);
            int tier = u < tierShare[0] ? 1 : u < tierShare[0] + tierShare[1] ? 2 : 3;
            String name = String.format(Locale.ROOT, "svc-%03d", i);
            List<String> calls = new ArrayList<>();
            if (i > 0) {
                int lo = cfg.get("callsPerService").get(0).asInt(), hi = cfg.get("callsPerService").get(1).asInt();
                int k = Math.min(i, lo + (int) (rand.u("ncalls", i) * (hi - lo + 1)));
                Set<Integer> picked = new HashSet<>();
                for (int j = 0; picked.size() < k && j < 50; j++) picked.add((int) (rand.u("call", i, j) * i));
                for (int p : picked) calls.add(String.format(Locale.ROOT, "svc-%03d", p));
            }
            double bm = cfg.get("businessMetricShare").get(tier - 1).asDouble();
            String metric = rand.u("metric", i) < bm ? metrics[(int) (rand.u("metricname", i) * metrics.length)] : null;
            String team = teams.get((int) (rand.u("team", i) * nTeams)).name();
            services.add(new Service(name, team, tier, calls, metric));
            complexity.put(name, Math.exp(cfg.get("complexitySd").asDouble() * rand.normal("complexity", i)));
        }
        this.catalog = new Catalog(services, teams);
        for (Service s : services) {
            for (Mode m : Mode.values()) {
                if (rand.u("existing", s.name(), m) < cfg.get("existingCoverage").get(m.name()).asDouble()) {
                    existing.add(new Monitor(s.name(), m, MonitorKind.STATIC_THRESHOLD, rand.u("routed", s.name(), m) < cfg.get("existingRoutedToOwner").asDouble()));
                }
            }
            JsonNode c = cfg.get("cost");
            double cpu = c.get("cpuRequested").get(0).asDouble() + rand.u("cpu", s.name()) * (c.get("cpuRequested").get(1).asDouble() - c.get("cpuRequested").get(0).asDouble());
            double over = c.get("overprovisionMedian").asDouble() * Math.exp(c.get("overprovisionSd").asDouble() * rand.normal("over", s.name()));
            usage.add(new CostLens.Usage(s.name(), cpu, cpu / over, cpu * 2, cpu * 2 / over));
        }
        int dep = 0;
        for (int week = 0; week < historyWeeks + liveWeeks; week++) {
            for (Service s : services) {
                int n = rand.poisson(cfg.get("deploysPerServiceWeek").asDouble(), "ndeploys", s.name(), week);
                for (int k = 0; k < n; k++) {
                    String id = String.format(Locale.ROOT, "d%06d", dep++);
                    int lines = Math.max(1, (int) Math.round(cfg.get("changedLines").get("median").asDouble() * Math.exp(cfg.get("changedLines").get("sd").asDouble() * rand.normal("lines", id))));
                    double share = beta(cfg.get("testedShare").get("alpha").asDouble(), cfg.get("testedShare").get("beta").asDouble(), id);
                    int tested = (int) Math.round(lines * share);
                    boolean flagged = rand.u("flagged", id) < cfg.get("flaggedShare").asDouble();
                    long minute = week * WEEK + (long) (rand.u("when", id) * 5) * 24 * 60 + 8 * 60 + (long) (rand.u("hour", id) * 10 * 60);
                    deploys.add(new Deploy(new Change(id, s.name(), lines, tested, flagged), week, minute));
                }
            }
        }
        deploys.sort(Comparator.comparingLong(Deploy::minute).thenComparing(d -> d.change().id()));
    }

    private double beta(double a, double b, String id) {
        double x = gamma(a, "ga", id), y = gamma(b, "gb", id);
        return x / (x + y);
    }

    private double gamma(double shape, String tag, String id) {
        // Marsaglia–Tsang for shape >= 1
        double d = shape - 1.0 / 3, c = 1 / Math.sqrt(9 * d);
        for (int i = 0; ; i++) {
            double x = rand.normal(tag, id, i), v = Math.pow(1 + c * x, 3);
            if (v > 0 && Math.log(rand.u(tag + "u", id, i)) < 0.5 * x * x + d - d * v + d * Math.log(v)) return d * v;
        }
    }

    private static double[] doubles(JsonNode n) {
        double[] out = new double[n.size()];
        for (int i = 0; i < n.size(); i++) out[i] = n.get(i).asDouble();
        return out;
    }

    public Observed observed() {
        if (observed == null) {
            Outcome hist = play(new Plan(existing, Map.of(), false, false), 0, historyWeeks);
            List<Observed.PastIncident> past = new ArrayList<>();
            for (Incident i : hist.incidents()) {
                if (i.detected() < historyWeeks * WEEK) {
                    past.add(new Observed.PastIncident(i.id(), i.service(), i.mode(), i.start(), i.byMonitor(), i.deployId(), i.signal()));
                }
            }
            List<Deploy> h = deploys.stream().filter(d -> d.week() < historyWeeks).toList();
            List<Deploy> live = deploys.stream().filter(d -> d.week() >= historyWeeks).toList();
            observed = new Observed(catalog, List.copyOf(existing), h, live, past, List.copyOf(usage), historyWeeks, liveWeeks, cfg.get("cost").get("cpuEurMonth").asDouble(),
                    cfg.get("cost").get("memGbEurMonth").asDouble());
        }
        return observed;
    }

    /** Plays a plan over weeks [fromWeek, fromWeek + weeks). */
    public Outcome play(Plan plan, int fromWeek, int weeks) {
        long end = (long) (fromWeek + weeks) * WEEK;
        Map<String, List<Monitor>> bySvcMode = new HashMap<>();
        for (Monitor m : plan.monitors()) bySvcMode.computeIfAbsent(m.service() + "/" + m.mode(), k -> new ArrayList<>()).add(m);

        // noise first: false alarms per team and week, from every monitor
        Map<String, int[]> falsePages = new HashMap<>(), truePages = new HashMap<>();
        for (Team t : catalog.teams()) {
            falsePages.put(t.name(), new int[weeks]);
            truePages.put(t.name(), new int[weeks]);
        }
        JsonNode fa = cfg.get("falseAlarmsPerMonitorWeek");
        for (Monitor m : plan.monitors()) {
            String team = catalog.service(m.service()).team();
            for (int w = 0; w < weeks; w++) {
                falsePages.get(team)[w] += rand.poisson(fa.get(m.kind().name()).asDouble(), "fa", m.service(), m.mode(), m.kind(), fromWeek + w);
            }
        }

        // failures: deploys that carry a bug, then recurrences of incidents whose follow-up actions were not done
        PriorityQueue<Pending> queue = new PriorityQueue<>(Comparator.comparingLong(Pending::start).thenComparing(Pending::id));
        double testHours = 0;
        double perKloc = cfg.get("bugsPerUntestedKloc").asDouble(), base = cfg.get("baseBugRate").asDouble();
        for (Deploy d : deploys) {
            if (d.week() < fromWeek || d.week() >= fromWeek + weeks) continue;
            Change c = d.change();
            Gating g = plan.gates().getOrDefault(c.id(), new Gating(0, c.flagged(), false));
            testHours += g.addedTestedLines() / cfg.get("linesTestedPerHour").asDouble();
            double pBug = 1 - Math.exp(-base - perKloc * complexity.get(c.service()) * c.untestedLines() / 1000.0);
            if (rand.u("bug", c.id()) >= pBug) continue;
            // the bug sits in untested lines; tests added for those lines catch it before production
            double coveredOfUntested = c.untestedLines() == 0 ? 0 : Math.min(1.0, (double) g.addedTestedLines() / c.untestedLines());
            if (rand.u("caught", c.id()) < coveredOfUntested * cfg.get("testsCatchBug").asDouble()) continue;
            long start = d.minute() + (long) (rand.u("onset", c.id()) * 120);
            queue.add(new Pending(c.id(), c.service(), start, c.id(), g.flag(), g.canary(), false, null));
        }

        List<Incident> out = new ArrayList<>();
        Map<String, List<String>> fingerprintsSeen = new HashMap<>();
        while (!queue.isEmpty()) {
            Pending p = queue.poll();
            if (p.start() >= end) continue;
            Incident inc = resolve(p, bySvcMode, falsePages, fromWeek, plan, fingerprintsSeen);
            out.add(inc);
            int w = inc.week() - fromWeek;
            if (inc.byMonitor() && w >= 0 && w < weeks) truePages.get(inc.team())[w]++;
            // follow-up actions: done, or the same failure comes back
            double close = cfg.get("actionsCloseShare").get(plan.trackActions() ? "tracked" : "untracked").asDouble();
            if (rand.u("actions", inc.id()) >= close) {
                double h = cfg.get("recurrenceWeeklyIfOpen").asDouble();
                double u = rand.u("recur", inc.id());
                long delayWeeks = (long) Math.ceil(Math.log(1 - u) / Math.log(1 - h));
                long at = inc.resolved() + delayWeeks * WEEK - (long) (rand.u("recurday", inc.id()) * WEEK);
                if (at > inc.resolved()) queue.add(new Pending(inc.id() + "r", inc.service(), at, null, false, false, true, inc.signal()));
            }
        }
        out.sort(Comparator.comparingLong(Incident::start).thenComparing(Incident::id));
        return new Outcome(out, truePages, falsePages, testHours, weeks, fromWeek);
    }

    private record Pending(String id, String service, long start, String deployId, boolean flag, boolean canary, boolean recurrence, String signal) {}

    private Incident resolve(Pending p, Map<String, List<Monitor>> bySvcMode, Map<String, int[]> falsePages, int fromWeek, Plan plan, Map<String, List<String>> seen) {
        Service s = catalog.service(p.service());
        String key = p.recurrence() ? p.id() : p.deployId();
        Mode mode = p.recurrence() ? modeOf(p.signal()) : drawMode(key);
        String sev = drawSeverity(key);
        String signal = p.signal() != null ? p.signal() : signal(s.name(), mode, key);

        // who notices first: a monitor that can see this mode, or a customer
        long bestMonitor = Long.MAX_VALUE;
        for (Monitor m : bySvcMode.getOrDefault(s.name() + "/" + mode, List.of())) {
            if (!Coverage.sees(m.kind(), mode)) continue;
            if (m.kind() == MonitorKind.STATIC_THRESHOLD) {
                double miss = sev.equals("low") ? cfg.get("staticMissesLow").asDouble() : sev.equals("medium") ? cfg.get("staticMissesMedium").asDouble() : 0;
                if (rand.u("staticmiss", p.id(), m.service(), m.mode()) < miss) continue;
            }
            JsonNode r = cfg.get("detectMinutes").get(m.kind().name());
            double det = r.get(0).asDouble() + rand.u("detect", p.id(), m.kind()) * (r.get(1).asDouble() - r.get(0).asDouble());
            int w = (int) (p.start() / WEEK) - fromWeek;
            int[] fp = falsePages.get(s.team());
            int noise = w >= 0 && w < fp.length ? fp[w] : 0;
            double fatigue = 1 + Math.max(0, noise - cfg.get("comfortPagesPerTeamWeek").asDouble()) * cfg.get("slowdownPerExtraPage").asDouble();
            double ack = cfg.get("ackMinutes").asDouble() * fatigue + (m.routedToOwner() ? 0 : cfg.get("unownedRouteMinutes").asDouble());
            bestMonitor = Math.min(bestMonitor, p.start() + Math.round(det + ack));
        }
        long customer;
        if (mode == Mode.SILENT_DROP && !sev.equals("high")) {
            customer = p.start() + cfg.get("silentLowSeenAfterMinutes").asLong() / (sev.equals("medium") ? 4 : 1);
        } else {
            double med = cfg.get("customerReportMedianMinutes").get(sev).asDouble();
            customer = p.start() + Math.round(med * Math.exp(cfg.get("customerReportSd").asDouble() * rand.normal("customer", p.id())));
        }
        boolean byMonitor = bestMonitor <= customer;
        long detected = Math.min(bestMonitor, customer);

        // diagnosis: faster with the brief, faster still when the brief recognises a known fingerprint
        String fp = Fingerprint.of(s.name(), mode, signal);
        boolean linked = plan.brief() && seen.containsKey(fp);
        seen.computeIfAbsent(fp, k -> new ArrayList<>()).add(p.id());
        double diagnose = cfg.get("diagnoseMedianMinutes").asDouble() * Math.exp(cfg.get("diagnoseSd").asDouble() * rand.normal("diagnose", p.id()));
        if (plan.brief()) diagnose *= linked ? 0.3 : 0.6;
        JsonNode mit = cfg.get("mitigateMinutes");
        double mitigate = p.deployId() == null ? mit.get("fix").asDouble() : p.flag() ? mit.get("flag").asDouble() : mit.get("rollback").asDouble();

        double affected = cfg.get("affectedShare").get(sev).asDouble();
        double weight = catalog.blastRadius(s.name()) / 3.0;
        long resolved;
        double impact;
        boolean canaryCaught = false;
        long bake = cfg.get("canaryBakeMinutes").asLong();
        double canaryShare = cfg.get("canaryShare").asDouble();
        if (p.canary() && byMonitor && detected - p.start() <= bake) {
            // the canary's monitor fires during the bake: the rollout stops and rolls back automatically
            canaryCaught = true;
            resolved = detected + 3;
            impact = affected * canaryShare * (resolved - p.start()) * weight;
        } else {
            resolved = detected + Math.round(diagnose + mitigate);
            if (p.canary()) {
                long early = Math.min(bake, resolved - p.start());
                impact = affected * weight * (canaryShare * early + (resolved - p.start() - early));
            } else {
                impact = affected * weight * (resolved - p.start());
            }
        }
        return new Incident(p.id(), s.name(), s.team(), mode, sev, p.start(), detected, resolved, byMonitor, p.deployId(), p.recurrence(), linked, canaryCaught, impact, signal);
    }

    private Mode drawMode(String key) {
        double u = rand.u("mode", key), acc = 0;
        for (Mode m : Mode.values()) {
            acc += cfg.get("modeShare").get(m.name()).asDouble();
            if (u < acc) return m;
        }
        return Mode.ERROR_SPIKE;
    }

    private String drawSeverity(String key) {
        double u = rand.u("severity", key), acc = 0;
        for (String s : SEVERITIES) {
            acc += cfg.get("severityShare").get(s).asDouble();
            if (u < acc) return s;
        }
        return "high";
    }

    private String signal(String service, Mode mode, String key) {
        int line = 20 + (int) (rand.u("line", key) * 900);
        String pod = service + "-" + Long.toHexString(Rand.mix(seed ^ key.hashCode())).substring(0, 9) + "-x" + (int) (rand.u("pod", key) * 9999);
        return switch (mode) {
            case ERROR_SPIKE -> "NullPointerException in " + service + " BookingHandler.java:" + line + " on " + pod;
            case LATENCY -> "p95 above budget on " + service + " /v2/availability, 'slow query' took " + (800 + line) + " ms on " + pod;
            case SATURATION -> "connection pool exhausted on " + service + " (" + line + "/" + line + " in use) on " + pod;
            case SILENT_DROP -> "business metric flat on " + service + " for " + line + " minutes";
            case DEPENDENCY -> "upstream 503 from dependency of " + service + " after " + line + " retries on " + pod;
        };
    }

    private static Mode modeOf(String signal) {
        if (signal.startsWith("NullPointer")) return Mode.ERROR_SPIKE;
        if (signal.startsWith("p95")) return Mode.LATENCY;
        if (signal.startsWith("connection pool")) return Mode.SATURATION;
        if (signal.startsWith("business")) return Mode.SILENT_DROP;
        return Mode.DEPENDENCY;
    }

    /** Hidden: how bug-prone a service's code is. Only the evaluator and tests may ask. */
    public double complexityOf(String service) {
        return complexity.get(service);
    }

    public int liveFromWeek() {
        return historyWeeks;
    }

    public int liveWeeks() {
        return liveWeeks;
    }
}
