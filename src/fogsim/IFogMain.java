package fogsim;

import fogsim.Scheduler.Kind;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Usage:
 *   java -cp ... fogsim.IFogMain calibrate [--params params/tuned_params.properties]
 *   java -cp ... fogsim.IFogMain run --topo tiered|flat --wl P30|P60|P90|B60 [--sched ALL|FCFS,GREEDY,...]
 *        [--seeds 31-60] [--queue fifo|ps] [--wan 0.1] [--params ...] [--out results_ifog/x.csv]
 * One JVM = one iFogSim2/CloudSim world at a time (CloudSim keeps static state), so parallelise with several JVMs.
 */
public final class IFogMain {
    public static void main(String[] a) throws Exception {
        String mode = a.length > 0 ? a[0] : "calibrate";
        Map<String, String> o = new HashMap<>();
        for (int i = 1; i + 1 < a.length; i += 2) o.put(a[i].replaceFirst("^--", ""), a[i + 1]);
        Params p = Params.loadOrDefault(Paths.get(o.getOrDefault("params", "params/tuned_params.properties")));
        if (mode.equals("calibrate")) System.exit(calibrate(p));
        else if (mode.equals("run")) run(o, p);
        else throw new IllegalArgumentException("mode must be calibrate or run");
    }

    static void run(Map<String, String> o, Params p) throws IOException {
        String topo = o.getOrDefault("topo", "tiered"), wl = o.getOrDefault("wl", "P60");
        IFogDispatcher.Queue qm = IFogDispatcher.Queue.valueOf(o.getOrDefault("queue", "fifo").toUpperCase());
        double wan = Double.parseDouble(o.getOrDefault("wan", String.valueOf(Topology.WAN_DEFAULT)));
        String[] sr = o.getOrDefault("seeds", "31-60").split("-");
        long s0 = Long.parseLong(sr[0]), s1 = Long.parseLong(sr[sr.length - 1]);
        List<Kind> kinds = new ArrayList<>();
        String sp = o.getOrDefault("sched", "ALL");
        if (sp.equals("ALL")) kinds.addAll(Arrays.asList(Kind.values())); else for (String k : sp.split(",")) kinds.add(Kind.valueOf(k));
        Path out = Paths.get(o.getOrDefault("out", "results_ifog/ifog_" + qm.name().toLowerCase() + "_" + topo + "_" + wl + ".csv"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        String scenario = "ifog_" + qm.name().toLowerCase();

        Node[] proto = Experiments.topo(topo, wan, 1);
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write(Experiments.HEADER); w.newLine();
            for (long seed = s0; seed <= s1; seed++) {
                Task[] tasks = Workload.byName(wl).generate(seed);     // identical task stream to the custom simulator
                for (Kind k : kinds) {
                    Simulator.Result r = IFogRunner.run(proto, tasks, k, p, seed, topo, wl, qm);
                    w.write(Experiments.row(scenario, r)); w.newLine(); w.flush();
                }
                System.out.printf("%s %s %s seed %d done%n", scenario, topo, wl, seed);
            }
        }
    }

    /**
     * Cross-check on identical decisions. PASS/FAIL compares iFogSim2 with ReferenceSim (true FIFO by arrival at the node).
     * The last two columns are informational: how far the original Simulator.run (lane served in decision order) is
     * from that reference.
     */
    static int calibrate(Params p) {
        final double TOL_MEAN_S = 5e-4, TOL_RATE = 0.005;   // 0.5 ms on mean response, 0.5 percentage points on deadline rates
        int bad = 0;
        System.out.println("                                    iFogSim2 - reference            | original Simulator - reference");
        System.out.println("case                               dMean(ms)  dDl      dCrit    result |  dMean(ms)  dDl      dCrit");

        // (1) a single task on a single node: iFogSim2 execution time must equal 2*delta + transfer + L/mu
        Node[] one = { new Node(0, Node.Tier.EDGE, 4000, 512, 50, 0.002, 87.53) };
        Task[] single = { new Task(0, 0.5, Task.Cls.HEALTH, 40, 0.1, 0.02, 32, 1, 0.1, 0.9) };
        bad += compare("single task, 1 node", one, single, Kind.FCFS, p, 1, "tiered", "single", TOL_MEAN_S, TOL_RATE);

        // (2) realistic streams
        Node[] tiered = Topology.tiered(Topology.WAN_DEFAULT, 1);
        for (String wl : new String[]{"P30", "B60"}) for (long seed : new long[]{1, 2, 3})
            for (Kind k : new Kind[]{Kind.FCFS, Kind.GREEDY, Kind.PROPOSED})
                bad += compare(k + " " + wl + " seed " + seed, tiered, Workload.byName(wl).generate(seed), k, p, seed, "tiered", wl, TOL_MEAN_S, TOL_RATE);

        System.out.println(bad == 0 ? "CALIBRATION PASSED: iFogSim2 reproduces the arrival-order FIFO reference within tolerance."
            : "CALIBRATION FAILED in " + bad + " case(s): do not use iFogSim2 results yet (see README_IFOGSIM2.md, section 'If calibrate fails').");
        return bad == 0 ? 0 : 1;
    }

    static int compare(String label, Node[] proto, Task[] tasks, Kind k, Params p, long seed, String topo, String wl, double tolMean, double tolRate) {
        Simulator.Result orig = Simulator.run(proto, tasks, k, p, seed, topo, wl);
        Simulator.Result ref = ReferenceSim.run(proto, tasks, k, p, seed, topo, wl);
        Simulator.Result port = IFogRunner.run(proto, tasks, k, p, seed, topo, wl, IFogDispatcher.Queue.FIFO);
        double dm = port.meanResp - ref.meanResp, dd = port.dlRate - ref.dlRate, dc = crit(port) - crit(ref);
        boolean ok = Math.abs(dm) <= tolMean && Math.abs(dd) <= tolRate && Math.abs(dc) <= tolRate;
        System.out.printf(Locale.ROOT, "%-34s %9.4f %8.4f %8.4f   %-8s| %9.4f %8.4f %8.4f%n", label, dm * 1000, dd, dc, ok ? "ok" : "MISMATCH",
            (orig.meanResp - ref.meanResp) * 1000, orig.dlRate - ref.dlRate, crit(orig) - crit(ref));
        return ok ? 0 : 1;
    }

    static double crit(Simulator.Result r) { return Double.isNaN(r.critDlRate) ? 0 : r.critDlRate; }
}
