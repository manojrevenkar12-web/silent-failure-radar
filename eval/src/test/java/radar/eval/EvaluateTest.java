package radar.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import radar.core.Settings;
import radar.world.World;
import tools.jackson.databind.JsonNode;

class EvaluateTest {
    @Test
    void designsSeeOnlyWhatIsObserved() throws Exception {
        for (String f : List.of("Designs.java", "Metrics.java")) {
            String src = Files.readString(Path.of("eval/src/main/java/radar/eval/" + f));
            assertFalse(src.contains("complexityOf") || src.contains(".play("), f);
        }
    }

    @Test
    void radarBeatsTheStatusQuoOnOneSeed() {
        Evaluate.SeedResult r = Evaluate.runSeed(11);
        double sq = Metrics.sum(r.weeks().get("status_quo"), Metrics.Week::impact);
        double radar = Metrics.sum(r.weeks().get("radar"), Metrics.Week::impact);
        assertTrue(radar < 0.6 * sq, radar + " vs " + sq);
        assertEquals(r.weeks().get("radar").size(), r.weeks().get("status_quo").size());
        assertEquals(sumDeploys(r, "radar"), sumDeploys(r, "status_quo"), "every design meets the same deploys");
    }

    private static int sumDeploys(Evaluate.SeedResult r, String d) {
        return r.weeks().get(d).stream().mapToInt(Metrics.Week::deploys).sum();
    }

    @Test
    void heldOutRunsOnce(@TempDir Path dir) throws Exception {
        System.setProperty("radar.reports", dir.toString());
        try {
            Files.writeString(dir.resolve("heldout.lock"), "{\"ran_at\": \"earlier\"}\n");
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> Evaluate.run("heldout"));
            assertTrue(e.getMessage().contains("run once"));
        } finally {
            System.clearProperty("radar.reports");
        }
    }

    @Test
    void gateNamesWhatFailed() throws Exception {
        JsonNode good = Settings.JSON.readTree("""
            {"designs": {"status_quo": {"false_page_share": [0.9, 0.8, 1.0]},
                         "radar": {"monitor_detected_share": [0.45, 0.4, 0.5], "impact_cut_vs_status_quo": [0.79, 0.74, 0.83],
                                   "pages_per_team_week": [4.8, 4, 5], "false_page_share": [0.78, 0.7, 0.8]}}}""");
        assertTrue(Report.gate(good, Settings.load("gates")).isEmpty());
        JsonNode bad = Settings.JSON.readTree(good.toString().replace("[4.8, 4, 5]", "[9.0, 8, 10]").replace("[4.8,4,5]", "[9.0,8,10]"));
        assertFalse(Report.gate(bad, Settings.load("gates")).isEmpty());
    }

    @Test
    void worldWeeksLineUp() {
        World w = World.simulate(1);
        assertEquals(8, w.liveFromWeek());
        assertEquals(16, w.liveWeeks());
    }
}
