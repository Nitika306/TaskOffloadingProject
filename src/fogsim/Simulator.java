package fogsim;

import fogsim.Node.Tier;
import java.util.*;

/** Discrete-event dispatch loop. Tasks are decided in arrival order using the scheduler's estimate (state at decision
 *  time); the *actual* response time is then measured on per-node FIFO lanes, so model error (stale queue, transfer
 *  delay before the task reaches the node) is part of the result rather than assumed away. */
public final class Simulator {

    public static final class Result {
        public String sched, topo, wl; public long seed; public int n;
        public double meanResp, p95Resp, p99Resp, meanOff, dlRate, critDlRate, critMeanResp, critP99,
                      energyPerTask, decisionUs, utilCV, maxUtil, fracEdge, fracReg, fracCloud, meanResp_analytics;
    }

    public static Result run(Node[] proto, Task[] tasks, Scheduler.Kind kind, Params p, long seed, String topo, String wl) {
        Node[] nodes = Topology.fresh(proto);
        Scheduler s = new Scheduler(kind);
        if (s.gate()) for (Node v : nodes) if (v.tier == Tier.EDGE) v.resFrac = p.resFrac;
        Random rng = new Random(seed * 31L + kind.ordinal());

        int n = tasks.length;
        double[] resp = new double[n]; boolean[] met = new boolean[n]; boolean[] crit = new boolean[n];
        double offSum = 0, energy = 0, lastFinish = 0; long decNs = 0;
        int[] tierCnt = new int[3];

        for (int i = 0; i < n; i++) {
            Task t = tasks[i]; double now = t.t;
            long t0 = System.nanoTime();
            Node v = s.assign(t, nodes, now, rng, p);
            decNs += System.nanoTime() - t0;

            boolean isCrit = t.u >= p.ucrit;
            int lane = (s.gate() && isCrit && v.resFrac > 0) ? 1 : 0;
            double rate = v.rate(lane), exe = t.L / rate;
            double arrive = now + v.delta + t.din / v.bw;
            double start = Math.max(arrive, v.freeAt[lane]);
            double finish = start + exe;
            v.freeAt[lane] = finish;
            double back = finish + v.delta + t.dout / v.bw;
            resp[i] = back - now; met[i] = resp[i] <= t.D; crit[i] = isCrit;
            offSum += 2 * v.delta + (t.din + t.dout) / v.bw;
            energy += v.power * exe + Eval.PTX * (t.din + t.dout) / v.bw;
            v.busyMI += t.L; v.assigned++; tierCnt[v.tier.ordinal()]++;
            lastFinish = Math.max(lastFinish, back);
        }

        Result r = new Result(); r.sched = kind.name(); r.topo = topo; r.wl = wl; r.seed = seed; r.n = n;
        r.meanResp = mean(resp, null); r.p95Resp = pct(resp, null, 0.95); r.p99Resp = pct(resp, null, 0.99);
        r.meanOff = offSum / n; r.energyPerTask = energy / n; r.decisionUs = decNs / 1e3 / n;
        int ok = 0, c = 0, cok = 0; for (int i = 0; i < n; i++) { if (met[i]) ok++; if (crit[i]) { c++; if (met[i]) cok++; } }
        r.dlRate = (double) ok / n; r.critDlRate = c == 0 ? Double.NaN : (double) cok / c;
        r.critMeanResp = mean(resp, crit); r.critP99 = pct(resp, crit, 0.99);
        boolean[] an = new boolean[n]; for (int i = 0; i < n; i++) an[i] = tasks[i].cls == Task.Cls.ANALYTICS;
        r.meanResp_analytics = mean(resp, an);
        double horizon = Math.max(lastFinish, Workload.DURATION);
        double[] util = new double[nodes.length]; double um = 0, mx = 0;
        for (int i = 0; i < nodes.length; i++) { util[i] = nodes[i].busyMI / (nodes[i].mu * horizon); um += util[i]; mx = Math.max(mx, util[i]); }
        um /= nodes.length; double var = 0; for (double u : util) var += (u - um) * (u - um); var /= nodes.length;
        r.utilCV = um > 0 ? Math.sqrt(var) / um : 0; r.maxUtil = mx;
        r.fracEdge = (double) tierCnt[0] / n; r.fracReg = (double) tierCnt[1] / n; r.fracCloud = (double) tierCnt[2] / n;
        return r;
    }

    static double mean(double[] a, boolean[] mask) {
        double s = 0; int c = 0; for (int i = 0; i < a.length; i++) if (mask == null || mask[i]) { s += a[i]; c++; }
        return c == 0 ? Double.NaN : s / c;
    }
    static double pct(double[] a, boolean[] mask, double q) {
        int c = 0; for (int i = 0; i < a.length; i++) if (mask == null || mask[i]) c++;
        if (c == 0) return Double.NaN;
        double[] b = new double[c]; int k = 0; for (int i = 0; i < a.length; i++) if (mask == null || mask[i]) b[k++] = a[i];
        Arrays.sort(b); return b[(int) Math.min(c - 1, Math.floor(q * c))];
    }
}
