package radar.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Infrastructure cost per team, and what right-sizing requests to observed usage would save. */
public final class CostLens {
    private CostLens() {}

    /** Kubernetes requests and the p95 of observed usage for one service. */
    public record Usage(String service, double cpuRequested, double cpuP95, double memGbRequested, double memGbP95) {}

    public record Line(String team, double eurPerMonth, double wasteEurPerMonth) {}

    public static double eur(double cpu, double memGb, double cpuEur, double memEur) {
        return cpu * cpuEur + memGb * memEur;
    }

    /** Waste: requested above p95 usage plus 20% headroom. */
    public static double waste(Usage u, double cpuEur, double memEur) {
        double cpu = Math.max(0, u.cpuRequested() - u.cpuP95() * 1.2);
        double mem = Math.max(0, u.memGbRequested() - u.memGbP95() * 1.2);
        return eur(cpu, mem, cpuEur, memEur);
    }

    public static List<Line> byTeam(Catalog catalog, Collection<Usage> usage, double cpuEur, double memEur) {
        Map<String, double[]> acc = new LinkedHashMap<>();
        for (Usage u : usage) {
            double[] a = acc.computeIfAbsent(catalog.service(u.service()).team(), k -> new double[2]);
            a[0] += eur(u.cpuRequested(), u.memGbRequested(), cpuEur, memEur);
            a[1] += waste(u, cpuEur, memEur);
        }
        List<Line> out = new ArrayList<>();
        acc.forEach((t, a) -> out.add(new Line(t, a[0], a[1])));
        out.sort(Comparator.comparingDouble(Line::wasteEurPerMonth).reversed());
        return out;
    }
}
