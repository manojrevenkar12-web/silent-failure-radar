package radar.eval;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import radar.core.CostLens;
import radar.core.Coverage;
import radar.core.DeployRisk;
import radar.core.Gap;
import radar.core.Settings;
import radar.world.Observed;
import radar.world.World;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs every design on a seed set and writes reports/&lt;set&gt;.json; writes the README block; checks the gates.
 *
 * Development seeds run as often as needed. Held-out seeds run once: the first run writes reports/heldout.lock with the
 * config fingerprint, and a second run refuses (AGENTS.md).
 */
public final class Evaluate {
    static Path reports() {
        return Path.of(System.getProperty("radar.reports", "reports"));
    }
    static final JsonMapper OUT = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private Evaluate() {}

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "dev";
        switch (cmd) {
            case "dev", "heldout" -> {
                try {
                    run(cmd);
                } catch (IllegalStateException e) {
                    System.err.println(e.getMessage());
                    System.exit(2);
                }
            }
            case "readme" -> Report.write();
            case "gate" -> {
                List<String> fails = new ArrayList<>();
                for (String set : List.of("dev", "heldout")) {
                    Path p = reports().resolve(set + ".json");
                    if (Files.exists(p)) for (String f : Report.gate(Settings.JSON.readTree(Files.readString(p)), Settings.load("gates"))) fails.add(set + ": " + f);
                }
                fails.forEach(f -> System.out.println("GATE FAILED: " + f));
                if (!fails.isEmpty()) System.exit(1);
                System.out.println("gates passed");
            }
            default -> throw new IllegalArgumentException("usage: dev | heldout | readme | gate");
        }
    }

    public static String fingerprint() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String f : List.of("world.json", "radar.json", "gates.json", "seeds.json")) md.update(Files.readAllBytes(Path.of("config", f)));
        return HexFormat.of().formatHex(md.digest()).substring(0, 16);
    }

    static void run(String set) throws Exception {
        List<Long> seeds = new ArrayList<>();
        Settings.load("seeds").get(set).forEach(n -> seeds.add(n.asLong()));
        String fp = fingerprint();
        Files.createDirectories(reports());
        if (set.equals("heldout")) {
            Path lock = reports().resolve("heldout.lock");
            if (Files.exists(lock)) throw new IllegalStateException("held-out seeds already ran (" + Files.readString(lock).strip() + "); they run once. See AGENTS.md.");
            Files.writeString(lock, "{\"ran_at\": \"" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\", \"config_sha\": \"" + fp + "\", \"seeds\": " + seeds + "}\n");
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(seeds.size(), Runtime.getRuntime().availableProcessors()));
        List<Future<SeedResult>> fs = new ArrayList<>();
        for (long s : seeds) fs.add(pool.submit(() -> runSeed(s)));
        List<SeedResult> parts = new ArrayList<>();
        for (Future<SeedResult> f : fs) parts.add(f.get());
        pool.shutdown();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("set", set);
        doc.put("seeds", seeds);
        doc.put("config_sha", fp);
        doc.put("generated_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        doc.putAll(summarize(parts));
        Files.writeString(reports().resolve(set + ".json"), OUT.writeValueAsString(doc) + "\n", StandardCharsets.UTF_8);
        System.out.println("{\"set\": \"" + set + "\", \"seeds\": " + seeds + ", \"config_sha\": \"" + fp + "\"}");
    }

    record SeedResult(long seed, Map<String, List<Metrics.Week>> weeks, Map<String, Object> facts, List<Gap> topGaps, double[] riskWeights, double costMonthly,
                      double wasteMonthly) {}

    public static SeedResult runSeed(long seed) {
        World w = World.simulate(seed);
        Observed o = w.observed();
        DeployRisk risk = Designs.fitRisk(o);
        Map<String, List<Metrics.Week>> weeks = new LinkedHashMap<>();
        for (String name : Designs.names()) {
            World.Plan plan = Designs.plan(name, o, risk, seed);
            World.Outcome out = w.play(plan, w.liveFromWeek(), w.liveWeeks());
            weeks.put(name, Metrics.weeks(out, o.liveDeploys()));
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("services", o.catalog().services().size());
        facts.put("teams", o.catalog().teams().size());
        facts.put("existing_monitors", o.existingMonitors().size());
        facts.put("history_incidents", o.pastIncidents().size());
        facts.put("history_caught_by_chance", (int) o.pastIncidents().stream().filter(p -> !p.caughtByMonitor()).count());
        facts.put("live_deploys", o.liveDeploys().size());
        List<Coverage.IncidentRecord> hist = o.pastIncidents().stream().map(p -> new Coverage.IncidentRecord(p.service(), p.mode(), p.caughtByMonitor())).toList();
        List<Gap> gaps = Coverage.gaps(o.catalog(), o.existingMonitors(), hist);
        facts.put("gaps", gaps.size());
        double cost = 0, waste = 0;
        for (CostLens.Usage u : o.usage()) {
            cost += CostLens.eur(u.cpuRequested(), u.memGbRequested(), o.cpuEur(), o.memEur());
            waste += CostLens.waste(u, o.cpuEur(), o.memEur());
        }
        return new SeedResult(seed, weeks, facts, gaps.subList(0, Math.min(8, gaps.size())), risk.weights(), cost, waste);
    }

    static Map<String, Object> summarize(List<SeedResult> parts) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> facts = new LinkedHashMap<>();
        for (String k : parts.get(0).facts().keySet()) facts.put(k, parts.stream().mapToInt(p -> ((Number) p.facts().get(k)).intValue()).sum());
        out.put("facts", facts);
        Map<String, Object> designs = new LinkedHashMap<>();
        List<Metrics.Week> sq = new ArrayList<>();
        for (SeedResult p : parts) sq.addAll(p.weeks().get("status_quo"));
        for (String name : Designs.names()) {
            List<Metrics.Week> all = new ArrayList<>();
            for (SeedResult p : parts) all.addAll(p.weeks().get(name));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", Designs.spec(name).get("label").asString());
            row.putAll(Metrics.summary(all, name.equals("status_quo") ? null : sq));
            designs.put(name, row);
        }
        out.put("designs", designs);
        JsonNode cost = Settings.load("world").get("cost");
        double monthly = parts.stream().mapToDouble(SeedResult::costMonthly).average().orElse(0);
        double waste = parts.stream().mapToDouble(SeedResult::wasteMonthly).average().orElse(0);
        out.put("cost", Map.of("eur_per_month", Metrics.round(monthly), "waste_eur_per_month", Metrics.round(waste),
                "saved_if_teams_act_eur_per_month", Metrics.round(waste * cost.get("rightsizeAdoption").asDouble())));
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (Gap g : parts.get(0).topGaps()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service", g.service());
            m.put("mode", g.mode().name());
            m.put("caught_by_chance", g.caughtByChance());
            m.put("score", Metrics.round(g.score()));
            m.put("fix", g.fix().name());
            gaps.add(m);
        }
        out.put("top_gaps_first_seed", gaps);
        out.put("risk_weights_first_seed", parts.get(0).riskWeights());
        return out;
    }

}
