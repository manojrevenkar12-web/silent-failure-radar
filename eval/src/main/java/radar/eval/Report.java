package radar.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import radar.core.Coverage;
import radar.core.Gap;
import radar.core.Monitors;
import radar.core.Settings;
import radar.world.Observed;
import radar.world.World;
import tools.jackson.databind.JsonNode;

/** REPORT.md, the README results block and the example monitors-as-code, all generated from reports/. */
public final class Report {
    private Report() {}

    static JsonNode load(String set) throws IOException {
        Path p = Evaluate.reports().resolve(set + ".json");
        return Files.exists(p) ? Settings.JSON.readTree(Files.readString(p)) : null;
    }

    static String pct(double x, int d) {
        return String.format(Locale.ROOT, "%." + d + "f%%", x * 100);
    }

    static String ci(JsonNode v, String fmt) {
        return String.format(Locale.ROOT, fmt + " (" + fmt + "–" + fmt + ")", v.get(0).asDouble(), v.get(1).asDouble(), v.get(2).asDouble());
    }

    static String ciPct(JsonNode v, int d) {
        return pct(v.get(0).asDouble(), d) + " (" + pct(v.get(1).asDouble(), d) + "–" + pct(v.get(2).asDouble(), d) + ")";
    }

    static String headline(JsonNode doc) {
        JsonNode d = doc.get("designs"), r = d.get("radar"), s = d.get("status_quo"), e = d.get("alert_everything");
        return String.format(Locale.ROOT,
                "Against today's monitors and straight-to-production deploys, the radar cut customer impact by **%s** (95%% interval %s–%s). Monitors caught %s of incidents instead of %s, "
                        + "median time to notice fell from %.0f to %.0f minutes and to recover from %.0f to %.0f minutes, and changes failed %s of the time instead of %s. "
                        + "Teams got %.1f pages a week, %s of them false; putting a static threshold on everything would mean %.1f pages a week, %s false.",
                pct(r.get("impact_cut_vs_status_quo").get(0).asDouble(), 0), pct(r.get("impact_cut_vs_status_quo").get(1).asDouble(), 0),
                pct(r.get("impact_cut_vs_status_quo").get(2).asDouble(), 0), pct(r.get("monitor_detected_share").get(0).asDouble(), 0),
                pct(s.get("monitor_detected_share").get(0).asDouble(), 0), s.get("mttd_median_min").get(0).asDouble(), r.get("mttd_median_min").get(0).asDouble(),
                s.get("mttr_median_min").get(0).asDouble(), r.get("mttr_median_min").get(0).asDouble(), pct(r.get("change_failure_rate").get(0).asDouble(), 1),
                pct(s.get("change_failure_rate").get(0).asDouble(), 1), r.get("pages_per_team_week").get(0).asDouble(), pct(r.get("false_page_share").get(0).asDouble(), 0),
                e.get("pages_per_team_week").get(0).asDouble(), pct(e.get("false_page_share").get(0).asDouble(), 0));
    }

    static String tables(JsonNode doc) {
        JsonNode f = doc.get("facts");
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT,
                "Seeds %s · %d services and %d teams per seed · %,d deploys over 16 live weeks · trained on 8 history weeks in which %d of %d recorded incidents were noticed by a customer or an engineer first · config `%s`. Intervals are 95%%, resampling whole weeks.%n%n",
                doc.get("seeds").toString().replaceAll("[\\[\\]]", "").replace(",", ", "), f.get("services").asInt() / doc.get("seeds").size(),
                f.get("teams").asInt() / doc.get("seeds").size(), f.get("live_deploys").asInt(), f.get("history_caught_by_chance").asInt(), f.get("history_incidents").asInt(),
                doc.get("config_sha").asString()));
        b.append("| Design | Customer impact per week | Caught by a monitor | Median minutes to notice | Median minutes to recover | Change failure rate | Pages per team-week | False pages | Recurrences per week |\n");
        b.append("|---|---|---|---|---|---|---|---|---|\n");
        doc.get("designs").properties().forEach(e -> {
            JsonNode r = e.getValue();
            String bold = e.getKey().equals("radar") ? "**" : "";
            b.append(String.format(Locale.ROOT, "| %s%s%s | %s | %s | %.0f | %.0f | %s | %.1f | %s | %.2f |%n", bold, r.get("label").asString(), bold,
                    ci(r.get("impact_per_week"), "%,.0f"), ciPct(r.get("monitor_detected_share"), 0), r.get("mttd_median_min").get(0).asDouble(),
                    r.get("mttr_median_min").get(0).asDouble(), pct(r.get("change_failure_rate").get(0).asDouble(), 2), r.get("pages_per_team_week").get(0).asDouble(),
                    pct(r.get("false_page_share").get(0).asDouble(), 0), r.get("recurrences_per_week").get(0).asDouble()));
        });
        JsonNode r = doc.get("designs").get("radar");
        b.append(String.format(Locale.ROOT, "%nThe gate asked for %.0f hours of tests a week across the fleet and stopped %d bad rollouts in the canary. ",
                r.get("test_hours_per_week").asDouble(), r.get("caught_in_canary").asInt()));
        JsonNode c = doc.get("cost");
        b.append(String.format(Locale.ROOT, "Requested compute costs €%,.0f a month per fleet, €%,.0f of it above p95 usage plus headroom; if teams act on 60%% of the right-sizing, that is €%,.0f a month.%n",
                c.get("eur_per_month").asDouble(), c.get("waste_eur_per_month").asDouble(), c.get("saved_if_teams_act_eur_per_month").asDouble()));
        b.append("\n**Top coverage gaps in the first seed** (what the radar fixes first):\n\n| Service | Failure mode | Incidents caught by chance | Score | Monitor it adds |\n|---|---|---|---|---|\n");
        for (JsonNode g : doc.get("top_gaps_first_seed")) {
            b.append(String.format(Locale.ROOT, "| `%s` | %s | %d | %.1f | %s |%n", g.get("service").asString(), g.get("mode").asString().toLowerCase(Locale.ROOT).replace('_', ' '),
                    g.get("caught_by_chance").asInt(), g.get("score").asDouble(), Monitors.label(radar.core.Mode.valueOf(g.get("mode").asString()))));
        }
        return b.toString();
    }

    public static void write() throws IOException {
        JsonNode dev = load("dev"), held = load("heldout");
        JsonNode main = held != null ? held : dev;
        if (main == null) throw new IllegalStateException("no reports yet: run ./gradlew :eval:evaluate first");
        String which = held != null ? "held-out" : "development";
        StringBuilder rep = new StringBuilder("# Results\n\nGenerated by `./gradlew :eval:readme` from `reports/*.json`. Do not edit.\n\n## ");
        rep.append(which.substring(0, 1).toUpperCase(Locale.ROOT)).append(which.substring(1)).append(" seeds\n\n").append(headline(main)).append("\n\n").append(tables(main));
        if (held != null && dev != null) rep.append("\n## Development seeds\n\n").append(tables(dev));
        Files.writeString(Path.of("REPORT.md"), rep.toString());
        String block = "_From the " + which + " seeds, run once; generated by `./gradlew :eval:readme`._\n\n" + headline(main) + "\n\n" + tables(main);
        Path readme = Path.of("README.md");
        if (Files.exists(readme)) {
            String t = Files.readString(readme);
            t = t.replaceAll("(?s)<!-- results:start -->.*<!-- results:end -->", java.util.regex.Matcher.quoteReplacement("<!-- results:start -->\n" + block + "\n<!-- results:end -->"));
            Files.writeString(readme, t);
        }
        examples(main.get("seeds").get(0).asLong());
    }

    /** The radar's first monitors for one simulated fleet, as Datadog API JSON and Terraform. */
    static void examples(long seed) throws IOException {
        World w = World.simulate(seed);
        Observed o = w.observed();
        List<Coverage.IncidentRecord> hist = o.pastIncidents().stream().map(p -> new Coverage.IncidentRecord(p.service(), p.mode(), p.caughtByMonitor())).toList();
        List<Gap> gaps = Coverage.gaps(o.catalog(), o.existingMonitors(), hist);
        Path dir = Path.of("examples");
        Files.createDirectories(dir.resolve("datadog"));
        try (var old = Files.list(dir.resolve("datadog"))) {
            for (Path p : old.filter(p -> p.toString().endsWith(".json")).toList()) Files.delete(p);
        }
        StringBuilder tf = new StringBuilder("# Generated by ./gradlew :eval:readme from the first seed's top coverage gaps. Do not edit.\n\n");
        List<String> names = new ArrayList<>();
        double slo = Settings.load("radar").get("sloTarget").asDouble();
        for (Gap g : gaps.subList(0, Math.min(6, gaps.size()))) {
            Map<String, Object> m = Monitors.datadog(o.catalog(), g.service(), g.mode(), slo);
            String file = g.service() + "-" + g.mode().name().toLowerCase(Locale.ROOT).replace('_', '-') + ".json";
            Files.writeString(dir.resolve("datadog").resolve(file), Evaluate.OUT.writeValueAsString(m) + "\n");
            tf.append(Monitors.terraform(m)).append('\n');
            names.add(file);
        }
        Files.writeString(dir.resolve("monitors.tf"), tf.toString());
    }

    public static List<String> gate(JsonNode doc, JsonNode gates) {
        JsonNode g = gates.get("radar"), r = doc.get("designs").get("radar"), s = doc.get("designs").get("status_quo");
        List<String> fails = new ArrayList<>();
        if (r.get("monitor_detected_share").get(0).asDouble() < g.get("monitorDetectedShareMin").asDouble()) fails.add("monitor-detected share below " + g.get("monitorDetectedShareMin"));
        if (r.get("impact_cut_vs_status_quo").get(1).asDouble() < g.get("impactCutVsStatusQuoLowMin").asDouble()) fails.add("impact cut low end below " + g.get("impactCutVsStatusQuoLowMin"));
        if (r.get("pages_per_team_week").get(0).asDouble() > g.get("pagesPerTeamWeekMax").asDouble()) fails.add("pages per team-week above " + g.get("pagesPerTeamWeekMax"));
        if (r.get("false_page_share").get(0).asDouble() > s.get("false_page_share").get(0).asDouble() * g.get("falsePageShareMaxOfStatusQuo").asDouble()) fails.add("false pages not below the status quo");
        return fails;
    }
}
