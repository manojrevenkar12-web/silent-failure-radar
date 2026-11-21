package radar.world;

import java.util.SplittableRandom;

/**
 * Keyed randomness: every draw is a function of the seed and a key, such as ("bug", deploy id). Two designs that
 * ask for the same key get the same number, which is what "same luck for every design" means here.
 */
public final class Rand {
    private final long seed;

    public Rand(long seed) {
        this.seed = seed;
    }

    public SplittableRandom stream(Object... key) {
        long h = seed * 0x9E3779B97F4A7C15L;
        for (Object k : key) h = mix(h ^ k.toString().hashCode() * 0xC2B2AE3D27D4EB4FL + (k instanceof Number n ? n.longValue() * 0x165667B19E3779F9L : 0));
        return new SplittableRandom(h);
    }

    public double u(Object... key) {
        return stream(key).nextDouble();
    }

    public double normal(Object... key) {
        SplittableRandom r = stream(key);
        double u1 = Math.max(1e-12, r.nextDouble()), u2 = r.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    public int poisson(double lambda, Object... key) {
        SplittableRandom r = stream(key);
        double l = Math.exp(-lambda), p = 1;
        int k = 0;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > l);
        return k - 1;
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
