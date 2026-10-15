package fogsim;

import fogsim.Task.Cls;
import java.util.*;

/** Synthetic IoT task streams: Poisson or bursty (two-state Markov-modulated Poisson) arrivals, three task classes. */
public final class Workload {
    public final String name; public final boolean bursty; public final double rate;
    public Workload(String name, boolean bursty, double rate) { this.name = name; this.bursty = bursty; this.rate = rate; }

    public static final Workload[] ALL = {
        new Workload("P30", false, 30), new Workload("P60", false, 60),
        new Workload("P90", false, 90), new Workload("B60", true, 60)
    };
    public static Workload byName(String n) { for (Workload w : ALL) if (w.name.equals(n)) return w; throw new IllegalArgumentException(n); }

    public static final double DURATION = 30.0; // s of arrivals

    static double u(Random r, double a, double b) { return a + (b - a) * r.nextDouble(); }

    public Task[] generate(long seed) {
        Random r = new Random(seed * 7919L + name.hashCode());
        List<Task> out = new ArrayList<>();
        double t = 0; int id = 0;
        // bursty: high state (3x... see below) mean 2 s, low state mean 6 s; rates 2.5*rate and 0.5*rate -> long-run mean = rate
        boolean high = false; double stateEnd = bursty ? expo(r, 6.0) : Double.MAX_VALUE;
        while (true) {
            double lam = bursty ? (high ? 2.5 * rate : 0.5 * rate) : rate;
            double dt = expo(r, 1.0 / lam);
            if (bursty && t + dt > stateEnd) { // state switch before next arrival
                t = stateEnd; high = !high; stateEnd = t + expo(r, high ? 2.0 : 6.0); continue;
            }
            t += dt;
            if (t >= DURATION) break;
            out.add(make(r, id++, t));
        }
        return out.toArray(new Task[0]);
    }

    static double expo(Random r, double mean) { return -mean * Math.log(1.0 - r.nextDouble()); }

    static Task make(Random r, int id, double t) {
        double p = r.nextDouble();
        if (p < 0.4)  // healthcare monitoring: tiny, urgent, tight deadline
            return new Task(id, t, Cls.HEALTH, u(r, 20, 80), u(r, 0.05, 0.2), 0.02, u(r, 16, 64), 1, u(r, 0.05, 0.12), u(r, 0.85, 1.0));
        if (p < 0.7)  // traffic control: medium
            return new Task(id, t, Cls.TRAFFIC, u(r, 100, 400), u(r, 0.5, 2), 0.1, u(r, 64, 256), 5, u(r, 0.3, 0.8), u(r, 0.4, 0.7));
        // analytics: heavy, delay-tolerant
        return new Task(id, t, Cls.ANALYTICS, u(r, 600, 2400), u(r, 5, 20), 1.0, u(r, 256, 1024), 20, u(r, 3, 10), u(r, 0.0, 0.3));
    }
}
