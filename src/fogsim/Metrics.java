package fogsim;

/** Metric definitions identical to Simulator.run, computed from raw per-task response times (no iFogSim2 dependency). */
final class Metrics {
    static Simulator.Result summarize(double[] resp, int gated, double offSum, double energy, long decNs, int[] tierCnt,
                                      double lastFinish, Node[] nodes, Task[] tasks, Scheduler.Kind kind, long seed, String topo, String wl) {
        int n = tasks.length;
        boolean[] met = new boolean[n], crit = new boolean[n], an = new boolean[n];
        for (int i = 0; i < n; i++) {
            met[i] = resp[i] <= tasks[i].D;
            crit[i] = tasks[i].cls == Task.Cls.HEALTH;
            an[i] = tasks[i].cls == Task.Cls.ANALYTICS;
        }
        Simulator.Result r = new Simulator.Result();
        r.sched = kind.name(); r.topo = topo; r.wl = wl; r.seed = seed; r.n = n;
        r.meanResp = Simulator.mean(resp, null); r.p95Resp = Simulator.pct(resp, null, 0.95); r.p99Resp = Simulator.pct(resp, null, 0.99);
        r.meanOff = offSum / n; r.energyPerTask = energy / n; r.decisionUs = decNs / 1e3 / n;
        int ok = 0, cn = 0, cok = 0;
        for (int i = 0; i < n; i++) { if (met[i]) ok++; if (crit[i]) { cn++; if (met[i]) cok++; } }
        r.gateRate = (double) gated / n; r.dlRate = (double) ok / n; r.critDlRate = cn == 0 ? Double.NaN : (double) cok / cn;
        r.critMeanResp = Simulator.mean(resp, crit); r.critP99 = Simulator.pct(resp, crit, 0.99);
        r.meanResp_analytics = Simulator.mean(resp, an);
        double horizon = Math.max(lastFinish, Workload.DURATION);
        double[] util = new double[nodes.length]; double um = 0, mx = 0;
        for (int i = 0; i < nodes.length; i++) { util[i] = nodes[i].busyMI / (nodes[i].mu * horizon); um += util[i]; mx = Math.max(mx, util[i]); }
        um /= nodes.length; double var = 0; for (double u : util) var += (u - um) * (u - um); var /= nodes.length;
        r.utilCV = um > 0 ? Math.sqrt(var) / um : 0; r.maxUtil = mx;
        r.fracEdge = (double) tierCnt[0] / n; r.fracReg = (double) tierCnt[1] / n; r.fracCloud = (double) tierCnt[2] / n;
        return r;
    }
}
