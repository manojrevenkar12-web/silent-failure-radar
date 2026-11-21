package radar.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where incidents are caught by chance instead of by a monitor, ranked by what each gap is worth closing.
 *
 * A (service, mode) is covered when a monitor of a kind that can see that mode is routed to the owner. A static
 * threshold counts as weak cover for errors and latency (it misses small regressions) and as no cover for silent drops.
 */
public final class Coverage {
    private Coverage() {}

    public static MonitorKind bestKind(Mode mode) {
        return mode == Mode.SILENT_DROP ? MonitorKind.ANOMALY : MonitorKind.BURN_RATE;
    }

    public static boolean sees(MonitorKind kind, Mode mode) {
        return switch (mode) {
            case SILENT_DROP -> kind == MonitorKind.ANOMALY;
            case ERROR_SPIKE, LATENCY, SATURATION, DEPENDENCY -> kind != MonitorKind.ANOMALY;
        };
    }

    /** Covered well: the best kind for the mode, routed to the owning team. */
    public static boolean coveredWell(Collection<Monitor> monitors, String service, Mode mode) {
        for (Monitor m : monitors) {
            if (m.service().equals(service) && m.mode() == mode && m.routedToOwner() && (m.kind() == bestKind(mode) || mode == Mode.SATURATION && sees(m.kind(), mode))) return true;
        }
        return false;
    }

    /**
     * Ranked gaps. {@code history} holds past incidents as (service, mode, caughtByMonitor).
     * Score = blast radius × (1 + incidents caught by chance in this mode + 0.5 × all incidents on the service).
     */
    public static List<Gap> gaps(Catalog catalog, Collection<Monitor> monitors, Collection<IncidentRecord> history) {
        Map<String, Integer> chance = new HashMap<>();
        Map<String, Integer> onService = new HashMap<>();
        for (IncidentRecord r : history) {
            onService.merge(r.service(), 1, Integer::sum);
            if (!r.caughtByMonitor()) chance.merge(r.service() + "/" + r.mode(), 1, Integer::sum);
        }
        List<Gap> out = new ArrayList<>();
        for (Service s : catalog.services()) {
            for (Mode m : Mode.values()) {
                if (m == Mode.SILENT_DROP && s.businessMetric() == null) continue;
                if (coveredWell(monitors, s.name(), m)) continue;
                int c = chance.getOrDefault(s.name() + "/" + m, 0);
                double score = catalog.blastRadius(s.name()) * (1 + c + 0.5 * onService.getOrDefault(s.name(), 0)) * modeWeight(m);
                out.add(new Gap(s.name(), m, c, score, bestKind(m)));
            }
        }
        out.sort(Comparator.comparingDouble(Gap::score).reversed().thenComparing(Gap::service).thenComparing(Gap::mode));
        return out;
    }

    /** Silent drops and error spikes hurt customers most per minute unseen. */
    static double modeWeight(Mode m) {
        return switch (m) {
            case SILENT_DROP -> 1.4;
            case ERROR_SPIKE -> 1.2;
            case LATENCY -> 1.0;
            case DEPENDENCY -> 0.9;
            case SATURATION -> 0.8;
        };
    }

    /** One past incident as the incident tracker knows it. */
    public record IncidentRecord(String service, Mode mode, boolean caughtByMonitor) {}
}
