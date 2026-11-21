package radar.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CoreTest {
    static Catalog fleet() {
        List<Team> teams = List.of(new Team("a", "slack-a", "pd-a"), new Team("b", "slack-b", "pd-b"));
        return new Catalog(List.of(
                new Service("db", "a", 2, List.of(), null),
                new Service("pay", "a", 1, List.of("db"), "payments.captured"),
                new Service("book", "b", 1, List.of("pay", "db"), "bookings.confirmed"),
                new Service("web", "b", 1, List.of("book"), null),
                new Service("tools", "b", 3, List.of(), null)), teams);
    }

    @Test
    void blastRadiusCountsEveryoneUpstreamOnce() {
        Catalog c = fleet();
        assertEquals(java.util.Set.of("pay", "book", "web"), c.dependents("db"));
        assertEquals(2 + 3 + 3 + 3, c.blastRadius("db"));
        assertEquals(1, c.blastRadius("tools"));
        assertThrows(IllegalArgumentException.class, () -> new Catalog(List.of(new Service("x", "nobody", 1, List.of(), null)), List.of()));
    }

    @Test
    void gapsRankBlastRadiusAndIncidentsCaughtByChance() {
        Catalog c = fleet();
        List<Monitor> monitors = List.of(new Monitor("pay", Mode.ERROR_SPIKE, MonitorKind.BURN_RATE, true), new Monitor("db", Mode.LATENCY, MonitorKind.STATIC_THRESHOLD, true));
        List<Gap> gaps = Coverage.gaps(c, monitors, List.of(new Coverage.IncidentRecord("book", Mode.SILENT_DROP, false), new Coverage.IncidentRecord("book", Mode.SILENT_DROP, false)));
        assertFalse(gaps.stream().anyMatch(g -> g.service().equals("pay") && g.mode() == Mode.ERROR_SPIKE), "a burn-rate monitor routed to the owner covers it");
        assertTrue(gaps.stream().anyMatch(g -> g.service().equals("db") && g.mode() == Mode.LATENCY), "a static threshold is not good cover");
        assertFalse(gaps.stream().anyMatch(g -> g.service().equals("web") && g.mode() == Mode.SILENT_DROP), "no business metric, no silent-drop gap");
        Gap top = gaps.get(0);
        assertEquals("book", top.service());
        assertEquals(Mode.SILENT_DROP, top.mode());
        assertEquals(MonitorKind.ANOMALY, top.fix());
    }

    @Test
    void monitorsAreRoutedToTheOwnerAndTerraformIsEscaped() {
        Map<String, Object> m = Monitors.datadog(fleet(), "book", Mode.ERROR_SPIKE, 0.999);
        assertTrue(((String) m.get("message")).contains("@pd-b @slack-b"), "tier 1 pages the owner");
        assertTrue(((String) m.get("query")).contains("> 0.0144"), "14.4 × the 0.1% budget");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) m.get("tags");
        assertTrue(tags.contains("team:b"));
        Map<String, Object> low = Monitors.datadog(fleet(), "tools", Mode.LATENCY, 0.999);
        assertFalse(((String) low.get("message")).contains("@pd-"), "tier 3 does not page");
        String tf = Monitors.terraform(m);
        assertTrue(tf.startsWith("resource \"datadog_monitor\" \"book_error_spike_slo_burn_rate\""));
        assertTrue(tf.contains("{{#is_alert}}") && !tf.contains("${"), tf);
    }

    @Test
    void riskModelLearnsThatUntestedCodeBreaks() {
        Random r = new Random(1);
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            int lines = 20 + r.nextInt(500);
            int tested = (int) (lines * r.nextDouble());
            Change c = new Change("d" + i, "pay", lines, tested, false);
            double p = 1 - Math.exp(-0.002 * c.untestedLines());
            xs.add(DeployRisk.features(c, 5, 0));
            ys.add(r.nextDouble() < p ? 1 : 0);
        }
        DeployRisk m = DeployRisk.fit(xs, ys, 0.001, 800);
        double safe = m.score(DeployRisk.features(new Change("x", "pay", 200, 200, false), 5, 0));
        double risky = m.score(DeployRisk.features(new Change("y", "pay", 200, 0, false), 5, 0));
        assertTrue(risky > 2 * safe, risky + " vs " + safe);
        assertEquals(DeployRisk.Gate.SHIP, DeployRisk.gate(0.01, 0.06, 0.15));
        assertEquals(DeployRisk.Gate.CANARY, DeployRisk.gate(0.1, 0.06, 0.15));
        assertEquals(DeployRisk.Gate.TESTS_FLAG_AND_CANARY, DeployRisk.gate(0.2, 0.06, 0.15));
        assertThrows(IllegalArgumentException.class, () -> new Change("z", "pay", 10, 11, false));
    }

    @Test
    void fingerprintIgnoresVolatileParts() {
        String a = Fingerprint.of("book", Mode.ERROR_SPIKE, "NullPointerException in book BookingHandler.java:120 on book-7d9f8c6b5-x2k4p id 3f2a1b4c-1111-2222-3333-444455556666");
        String b = Fingerprint.of("book", Mode.ERROR_SPIKE, "NullPointerException in book BookingHandler.java:987 on book-5b8c7d6e9-q9w8e id 9a9a9a9a-1111-2222-3333-444455556666");
        assertEquals(a, b);
        assertNotEquals(a, Fingerprint.of("pay", Mode.ERROR_SPIKE, "NullPointerException in book BookingHandler.java:120"));
        assertEquals("timeout after <n> ms for <v>", Fingerprint.normalise("Timeout after 3000 ms for 'GET /v2/x'"));
    }

    @Test
    void briefPointsAtTheFreshestSuspectAndKnownRepeats() {
        Catalog c = fleet();
        List<IncidentBrief.RecentDeploy> deploys = List.of(new IncidentBrief.RecentDeploy("d1", "pay", 100, false), new IncidentBrief.RecentDeploy("d2", "book", 150, false),
                new IncidentBrief.RecentDeploy("d3", "tools", 155, true), new IncidentBrief.RecentDeploy("old", "book", 10, true));
        IncidentBrief.Brief b = IncidentBrief.build(c, "book", 160, deploys, List.of(), 60);
        assertEquals(List.of("d2", "d1"), b.suspects().stream().map(IncidentBrief.RecentDeploy::id).toList());
        assertEquals("Roll back d2 on book", b.firstStep());
        assertEquals(List.of("web"), b.dependents());
        IncidentBrief.Brief known = IncidentBrief.build(c, "tools", 500, deploys, List.of(new IncidentBrief.Prior("inc-7", 100, List.of("fix"))), 60);
        assertTrue(known.firstStep().contains("inc-7"));
    }

    @Test
    void costWasteIsAboveP95PlusHeadroom() {
        CostLens.Usage u = new CostLens.Usage("pay", 4, 1, 8, 2);
        assertEquals((4 - 1.2) * 24 + (8 - 2.4) * 3.2, CostLens.waste(u, 24, 3.2), 1e-9);
        assertEquals("a", CostLens.byTeam(fleet(), List.of(u), 24, 3.2).get(0).team());
    }

    @Test
    void canarySplitIsAnIstioVirtualServiceAndRefusesBadNames() {
        String y = Rollout.istio("booking-api", "bookings", "d42", 5);
        assertTrue(y.contains("kind: VirtualService") && y.contains("weight: 95") && y.contains("weight: 5") && y.contains("exact: \"d42\""));
        assertThrows(IllegalArgumentException.class, () -> Rollout.istio("Booking API", "bookings", "d42", 5));
        assertThrows(IllegalArgumentException.class, () -> Rollout.istio("booking-api", "bookings", "d42\"; rm", 5));
        assertThrows(IllegalArgumentException.class, () -> Rollout.istio("booking-api", "bookings", "d42", 101));
    }
}
