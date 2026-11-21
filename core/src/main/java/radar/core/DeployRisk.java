package radar.core;

import java.util.List;

/**
 * How likely a change is to cause an incident, and what the pipeline should ask for before it ships.
 *
 * The score is a logistic regression over what CI and the catalog know: untested changed lines, change size,
 * blast radius and the service's recent incidents. It is fitted on past deploys and the incidents traced to them;
 * code then decides the gate from the score.
 */
public final class DeployRisk {
    public static final int FEATURES = 5;
    private final double[] w;

    public DeployRisk(double[] weights) {
        if (weights.length != FEATURES + 1) throw new IllegalArgumentException("expected " + (FEATURES + 1) + " weights");
        this.w = weights.clone();
    }

    public static double[] features(Change c, double blastRadius, int recentIncidents) {
        return new double[] {
            Math.log1p(c.untestedLines()) ,
            Math.log1p(c.changedLines()),
            1 - c.testedShare(),
            Math.log1p(blastRadius),
            Math.min(recentIncidents, 5),
        };
    }

    public double score(double[] x) {
        double z = w[0];
        for (int i = 0; i < FEATURES; i++) z += w[i + 1] * x[i];
        return 1 / (1 + Math.exp(-z));
    }

    public double[] weights() {
        return w.clone();
    }

    /** Fits the weights by L2-regularised gradient descent. Labels: 1 if an incident was traced to the deploy. */
    public static DeployRisk fit(List<double[]> xs, List<Integer> ys, double l2, int iterations) {
        double[] w = new double[FEATURES + 1];
        int n = xs.size();
        if (n == 0) throw new IllegalArgumentException("no training data");
        double pos = ys.stream().mapToInt(Integer::intValue).sum();
        w[0] = Math.log((pos + 1) / (n - pos + 1));
        double lr = 0.5;
        for (int it = 0; it < iterations; it++) {
            double[] g = new double[FEATURES + 1];
            for (int k = 0; k < n; k++) {
                double[] x = xs.get(k);
                double z = w[0];
                for (int i = 0; i < FEATURES; i++) z += w[i + 1] * x[i];
                double err = 1 / (1 + Math.exp(-z)) - ys.get(k);
                g[0] += err;
                for (int i = 0; i < FEATURES; i++) g[i + 1] += err * x[i];
            }
            for (int i = 0; i <= FEATURES; i++) w[i] -= lr * (g[i] / n + (i == 0 ? 0 : l2 * w[i]));
        }
        return new DeployRisk(w);
    }

    /** What the pipeline asks for. */
    public enum Gate { SHIP, CANARY, TESTS_FLAG_AND_CANARY }

    public static Gate gate(double risk, double canaryAt, double testsAt) {
        if (risk >= testsAt) return Gate.TESTS_FLAG_AND_CANARY;
        if (risk >= canaryAt) return Gate.CANARY;
        return Gate.SHIP;
    }
}
