package radar.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.ToDoubleFunction;
import radar.world.World;

/** Per-week numbers for one design on one seed, and the bootstrap over weeks that gives every number an interval. */
public final class Metrics {
    private Metrics() {}

    /** One week of one seed. */
    public record Week(double impact, int incidents, int byMonitor, int deploys, int deployIncidents, int recurrences, int truePages, int falsePages,
                       int teamWeeks, double testHours, List<Long> mttd, List<Long> mttr, int canaryCaught) {}

    public static List<Week> weeks(World.Outcome o, List<World.Deploy> liveDeploys) {
        List<Week> out = new ArrayList<>();
        for (int w = 0; w < o.weeks(); w++) {
            int week = o.firstWeek() + w;
            double impact = 0;
            int n = 0, mon = 0, depInc = 0, rec = 0, canary = 0;
            List<Long> mttd = new ArrayList<>(), mttr = new ArrayList<>();
            for (World.Incident i : o.incidents()) {
                if (i.week() != week) continue;
                n++;
                impact += i.impact();
                if (i.byMonitor()) mon++;
                if (i.deployId() != null) depInc++;
                if (i.recurrence()) rec++;
                if (i.caughtInCanary()) canary++;
                mttd.add(i.mttd());
                mttr.add(i.mttr());
            }
            int deploys = (int) liveDeploys.stream().filter(d -> d.week() == week).count();
            int tp = 0, fp = 0;
            for (int[] a : o.truePages().values()) tp += a[w];
            for (int[] a : o.falsePages().values()) fp += a[w];
            out.add(new Week(impact, n, mon, deploys, depInc, rec, tp, fp, o.truePages().size(), o.testHours() / o.weeks(), mttd, mttr, canary));
        }
        return out;
    }

    public static double median(List<Long> xs) {
        if (xs.isEmpty()) return Double.NaN;
        long[] a = xs.stream().mapToLong(Long::longValue).sorted().toArray();
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2.0;
    }

    /** Estimate and 95% interval of a statistic over weeks pooled across seeds, resampling whole weeks. */
    public static double[] boot(List<Week> weeks, ToDoubleFunction<List<Week>> stat) {
        double est = stat.applyAsDouble(weeks);
        SplittableRandom r = new SplittableRandom(42);
        double[] v = new double[400];
        for (int b = 0; b < v.length; b++) {
            List<Week> s = new ArrayList<>(weeks.size());
            for (int i = 0; i < weeks.size(); i++) s.add(weeks.get(r.nextInt(weeks.size())));
            v[b] = stat.applyAsDouble(s);
        }
        Arrays.sort(v);
        return new double[] {round(est), round(v[(int) (0.025 * v.length)]), round(v[(int) (0.975 * v.length) - 1])};
    }

    /** Paired bootstrap of a ratio between two designs on the same weeks. */
    public static double[] bootPaired(List<Week> a, List<Week> b, ToDoubleFunction<List<Week>> stat) {
        SplittableRandom r = new SplittableRandom(43);
        double est = 1 - stat.applyAsDouble(a) / stat.applyAsDouble(b);
        double[] v = new double[400];
        for (int k = 0; k < v.length; k++) {
            List<Week> sa = new ArrayList<>(), sb = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                int j = r.nextInt(a.size());
                sa.add(a.get(j));
                sb.add(b.get(j));
            }
            v[k] = 1 - stat.applyAsDouble(sa) / stat.applyAsDouble(sb);
        }
        Arrays.sort(v);
        return new double[] {round(est), round(v[(int) (0.025 * v.length)]), round(v[(int) (0.975 * v.length) - 1])};
    }

    static double sum(List<Week> ws, ToDoubleFunction<Week> f) {
        double s = 0;
        for (Week w : ws) s += f.applyAsDouble(w);
        return s;
    }

    public static Map<String, Object> summary(List<Week> ws, List<Week> statusQuo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("impact_per_week", boot(ws, x -> sum(x, Week::impact) / x.size()));
        if (statusQuo != null) m.put("impact_cut_vs_status_quo", bootPaired(ws, statusQuo, x -> sum(x, Week::impact)));
        m.put("change_failure_rate", boot(ws, x -> sum(x, Week::deployIncidents) / Math.max(1, sum(x, Week::deploys))));
        m.put("monitor_detected_share", boot(ws, x -> sum(x, Week::byMonitor) / Math.max(1, sum(x, Week::incidents))));
        m.put("mttd_median_min", boot(ws, x -> median(x.stream().flatMap(w -> w.mttd().stream()).toList())));
        m.put("mttr_median_min", boot(ws, x -> median(x.stream().flatMap(w -> w.mttr().stream()).toList())));
        m.put("pages_per_team_week", boot(ws, x -> (sum(x, Week::truePages) + sum(x, Week::falsePages)) / sum(x, Week::teamWeeks)));
        m.put("false_page_share", boot(ws, x -> sum(x, Week::falsePages) / Math.max(1, sum(x, Week::truePages) + sum(x, Week::falsePages))));
        m.put("recurrences_per_week", boot(ws, x -> sum(x, Week::recurrences) / x.size()));
        m.put("incidents_per_week", boot(ws, x -> sum(x, Week::incidents) / x.size()));
        m.put("test_hours_per_week", round(sum(ws, Week::testHours) / ws.size()));
        m.put("caught_in_canary", (int) sum(ws, Week::canaryCaught));
        m.put("incidents", (int) sum(ws, Week::incidents));
        return m;
    }

    static double round(double x) {
        return Double.isNaN(x) ? x : Math.round(x * 10000) / 10000.0;
    }
}
