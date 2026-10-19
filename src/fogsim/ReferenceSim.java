package fogsim;

import fogsim.Node.Tier;
import java.util.*;

/**
 * Reference for the iFogSim2 port: the decisions are exactly those of Simulator.run (the scheduler only sees the
 * drain-time estimate freeAt, updated in decision order), but the ACTUAL service of each lane is true FIFO by arrival
 * time at the node. Simulator.run serves a lane in decision order, so a task still in transit can block a task that
 * has already arrived; this class does not. Pure Java, no iFogSim2 needed.
 */
final class ReferenceSim {
    static Simulator.Result run(Node[] proto, Task[] tasks, Scheduler.Kind kind, Params p, long seed, String topo, String wl) {
        Node[] nodes = Topology.fresh(proto);
        Scheduler s = new Scheduler(kind);
        if (s.gate()) for (Node v : nodes) if (v.tier == Tier.EDGE) v.resFrac = p.resFrac;
        Random rng = new Random(seed * 31L + kind.ordinal());
        int n = tasks.length;
        int[] nodeOf = new int[n], laneOf = new int[n]; double[] arrive = new double[n], exe = new double[n];
        int gated = 0; double offSum = 0, energy = 0; long decNs = 0; int[] tierCnt = new int[3];
        for (int i = 0; i < n; i++) {
            Task t = tasks[i]; double now = t.t;
            long t0 = System.nanoTime();
            Node v = s.assign(t, nodes, now, rng, p);
            decNs += System.nanoTime() - t0;
            boolean gateOn = t.u >= p.ucrit; if (gateOn) gated++;
            int lane = (s.gate() && gateOn && v.resFrac > 0) ? 1 : 0;
            double e = t.L / v.rate(lane), a = now + v.delta + t.din / v.bw;
            v.freeAt[lane] = Math.max(a, v.freeAt[lane]) + e;       // the scheduler's view (drain-time estimate)
            nodeOf[i] = v.id; laneOf[i] = lane; arrive[i] = a; exe[i] = e;
            offSum += 2 * v.delta + (t.din + t.dout) / v.bw;
            energy += v.power * e + Eval.PTX * (t.din + t.dout) / v.bw;
            v.busyMI += t.L; v.assigned++; tierCnt[v.tier.ordinal()]++;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> arrive[i]).thenComparingInt(i -> i));
        double[] free = new double[nodes.length * 2]; double[] resp = new double[n]; double lastFinish = 0;
        for (int i : order) {
            int k = nodeOf[i] * 2 + laneOf[i]; Node v = nodes[nodeOf[i]];
            double finish = Math.max(arrive[i], free[k]) + exe[i]; free[k] = finish;
            double back = finish + v.delta + tasks[i].dout / v.bw;
            resp[i] = back - tasks[i].t; lastFinish = Math.max(lastFinish, back);
        }
        return Metrics.summarize(resp, gated, offSum, energy, decNs, tierCnt, lastFinish, nodes, tasks, kind, seed, topo, wl);
    }
}
