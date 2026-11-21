package radar.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import radar.core.Mode;
import radar.core.Monitor;
import radar.core.MonitorKind;

class WorldTest {
    @Test
    void sameSeedSameWorldAndSameOutcome() {
        World a = World.simulate(5), b = World.simulate(5);
        assertEquals(a.observed().liveDeploys(), b.observed().liveDeploys());
        World.Plan p = new World.Plan(a.observed().existingMonitors(), Map.of(), false, false);
        assertEquals(a.play(p, a.liveFromWeek(), 4).incidents(), b.play(p, b.liveFromWeek(), 4).incidents());
    }

    @Test
    void observedCarriesNoHiddenFacts() {
        List<String> fields = Arrays.stream(Observed.class.getRecordComponents()).map(c -> c.getName()).toList();
        assertFalse(fields.contains("complexity"));
        List<String> past = Arrays.stream(Observed.PastIncident.class.getRecordComponents()).map(c -> c.getName()).toList();
        assertFalse(past.contains("severity") || past.contains("impact"), "the tracker does not know true severity or impact");
    }

    @Test
    void aMonitorThatSeesTheModeCatchesWhatCustomersWouldReportLater() {
        World w = World.simulate(7);
        Observed o = w.observed();
        List<Monitor> all = o.catalog().services().stream()
                .flatMap(s -> Arrays.stream(Mode.values()).map(m -> new Monitor(s.name(), m, m == Mode.SILENT_DROP ? MonitorKind.ANOMALY : MonitorKind.BURN_RATE, true)))
                .toList();
        World.Outcome none = w.play(new World.Plan(List.of(), Map.of(), false, false), w.liveFromWeek(), 6);
        World.Outcome full = w.play(new World.Plan(all, Map.of(), false, false), w.liveFromWeek(), 6);
        assertTrue(none.incidents().stream().noneMatch(World.Incident::byMonitor));
        double mttdNone = none.incidents().stream().mapToLong(World.Incident::mttd).average().orElseThrow();
        double mttdFull = full.incidents().stream().mapToLong(World.Incident::mttd).average().orElseThrow();
        assertTrue(mttdFull < mttdNone / 5, mttdFull + " vs " + mttdNone);
    }

    @Test
    void tracedActionsPreventRecurrences() {
        World w = World.simulate(9);
        var m = w.observed().existingMonitors();
        long open = w.play(new World.Plan(m, Map.of(), false, false), w.liveFromWeek(), 16).incidents().stream().filter(World.Incident::recurrence).count();
        long tracked = w.play(new World.Plan(m, Map.of(), true, false), w.liveFromWeek(), 16).incidents().stream().filter(World.Incident::recurrence).count();
        assertTrue(tracked < open / 2, tracked + " vs " + open);
    }

    @Test
    void theWorldReadsOnlyItsOwnConfig() throws Exception {
        String src = Files.readString(Path.of("world/src/main/java/radar/world/World.java"));
        assertTrue(src.contains("Settings.load(\"world\")"));
        assertFalse(src.contains("Settings.load(\"radar\")") || src.contains("Settings.load(\"gates\")"));
    }
}
