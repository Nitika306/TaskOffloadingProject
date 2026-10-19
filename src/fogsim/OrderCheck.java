package fogsim;

import fogsim.Scheduler.Kind;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Pure Java (no iFogSim2). Quantifies the effect of the lane service order: Simulator.run (decision order) versus
 * ReferenceSim (true FIFO by arrival at the node) on identical decisions.
 * Usage: java -cp out fogsim.OrderCheck [params] [topo=tiered] [seeds=31-60] [workloads=P30,P60,P90,B60]
 */
public final class OrderCheck {
    public static void main(String[] a) throws Exception {
        Params p = Params.loadOrDefault(Paths.get(a.length > 0 ? a[0] : "params/tuned_params.properties"));
        String topo = a.length > 1 ? a[1] : "tiered";
        String[] sr = (a.length > 2 ? a[2] : "31-60").split("-");
        long s0 = Long.parseLong(sr[0]), s1 = Long.parseLong(sr[1]);
        String[] wls = (a.length > 3 ? a[3] : "P30,P60,P90,B60").split(",");
        Node[] proto = Experiments.topo(topo, Topology.WAN_DEFAULT, 1);
        System.out.printf("topology %s, seeds %d-%d. crit = critical-deadline rate, dl = overall deadline rate, mean = mean response (s)%n", topo, s0, s1);
        for (String wl : wls) {
            System.out.printf("%n%s%n%-15s %9s %9s | %9s %9s | %8s %8s%n", wl, "scheduler", "crit orig", "crit FIFO", "dl orig", "dl FIFO", "mean orig", "mean FIFO");
            for (Kind k : Kind.values()) {
                double[][] m = LongStream.rangeClosed(s0, s1).parallel().mapToObj(seed -> {
                    Task[] tasks = Workload.byName(wl).generate(seed);
                    Simulator.Result o = Simulator.run(proto, tasks, k, p, seed, topo, wl), r = ReferenceSim.run(proto, tasks, k, p, seed, topo, wl);
                    return new double[]{o.critDlRate, r.critDlRate, o.dlRate, r.dlRate, o.meanResp, r.meanResp};
                }).toArray(double[][]::new);
                double[] avg = new double[6];
                for (double[] x : m) for (int j = 0; j < 6; j++) avg[j] += x[j] / m.length;
                System.out.printf(Locale.ROOT, "%-15s %9.4f %9.4f | %9.4f %9.4f | %8.4f %8.4f%n", k, avg[0], avg[1], avg[2], avg[3], avg[4], avg[5]);
            }
        }
    }
}
