package fogsim;

import fogsim.Scheduler.Kind;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class Experiments {
    /** Seed separation: tuning 9001-9005; sensitivity/WAN 1..n; FINAL EVALUATION 31..30+n (never used for tuning or sensitivity). */
    static final int EVAL_FIRST = 31, SENS_FIRST = 1;

    static final String HEADER = "scenario,sched,topo,wl,seed,n,meanResp,p95Resp,p99Resp,meanOff,dlRate,critDlRate,gateRate,critMeanResp,critP99,"
        + "energyPerTask,decisionUs,utilCV,maxUtil,fracEdge,fracReg,fracCloud,meanRespAnalytics";

    static String row(String scenario, Simulator.Result r) {
        return String.format(Locale.ROOT, "%s,%s,%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.4f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
            scenario, r.sched, r.topo, r.wl, r.seed, r.n, r.meanResp, r.p95Resp, r.p99Resp, r.meanOff, r.dlRate, r.critDlRate, r.gateRate,
            r.critMeanResp, r.critP99, r.energyPerTask, r.decisionUs, r.utilCV, r.maxUtil, r.fracEdge, r.fracReg, r.fracCloud, r.meanResp_analytics);
    }

    interface Job { List<String> run(); }

    static void execute(List<Job> jobs, Path out) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
        List<Future<List<String>>> fs = new ArrayList<>();
        for (Job j : jobs) fs.add(ex.submit(j::run));
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write(HEADER); w.newLine(); int d = 0;
            for (Future<List<String>> f : fs) {
                for (String l : f.get()) { w.write(l); w.newLine(); }
                if (++d % 20 == 0) System.out.println("  " + d + "/" + jobs.size() + " jobs done");
            }
        }
        ex.shutdown();
    }

    static Node[] topo(String name, double wan, int scale) { return name.equals("flat") ? Topology.flat(scale) : Topology.tiered(wan, scale); }

    /** Main experiment: all schedulers x 2 topologies x 4 workloads x seeds 31..30+nSeeds (paired: same tasks for every scheduler).
     *  scale is the topology multiplier (scale=1 is the 8-node default; scale=2 is 15 nodes). */
    public static void main_(Path outDir, Params p, int nSeeds, int scale) throws Exception {
        List<Job> jobs = new ArrayList<>();
        for (String tn : new String[]{"tiered", "flat"}) for (Workload wl : Workload.ALL) for (int s = EVAL_FIRST; s < EVAL_FIRST + nSeeds; s++) {
            final String t = tn; final Workload w = wl; final long seed = s;
            jobs.add(() -> {
                Node[] proto = topo(t, Topology.WAN_DEFAULT, scale); Task[] tasks = w.generate(seed);
                List<String> rows = new ArrayList<>();
                for (Kind k : Kind.values()) rows.add(row("main", Simulator.run(proto, tasks, k, p, seed, t, w.name)));
                return rows;
            });
        }
        execute(jobs, outDir.resolve("main.csv"));
    }

    /** WAN-delay sweep on the tiered topology (tests the gate: cloud one-way delay 20 ms .. 400 ms). */
    public static void wan(Path outDir, Params p, int nSeeds) throws Exception {
        double[] d = {0.02, 0.05, 0.10, 0.20, 0.40};
        Kind[] ks = {Kind.GREEDY, Kind.BASE19, Kind.PROPOSED, Kind.PROPOSED_EXACT, Kind.ABL_GATECLOUD, Kind.ABL_NOGATE};
        List<Job> jobs = new ArrayList<>();
        for (double wd : d) for (int s = SENS_FIRST; s < SENS_FIRST + nSeeds; s++) {
            final double wan = wd; final long seed = s;
            jobs.add(() -> {
                Node[] proto = Topology.tiered(wan, 1); Task[] tasks = Workload.byName("P60").generate(seed);
                List<String> rows = new ArrayList<>();
                for (Kind k : ks) rows.add(row(String.format(Locale.ROOT, "wan=%.3f", wan), Simulator.run(proto, tasks, k, p, seed, "tiered", "P60")));
                return rows;
            });
        }
        execute(jobs, outDir.resolve("wan.csv"));
    }

    /** One-at-a-time sensitivity of PROPOSED to each of the eight tuned parameters (x0.5 and x1.5). */
    public static void sens(Path outDir, Params p, int nSeeds) throws Exception {
        List<Job> jobs = new ArrayList<>();
        List<String[]> variants = new ArrayList<>(); variants.add(new String[]{"base", "1.0"});
        for (String k : Params.TUNABLE) for (String f : new String[]{"0.5", "1.5"}) variants.add(new String[]{k, f});
        for (String f : new String[]{"0.0", "0.1", "0.2", "0.5"}) variants.add(new String[]{"resFrac", f}); // absolute values
        for (String f : new String[]{"0.6", "0.9"}) variants.add(new String[]{"ucrit", f});               // absolute values
        for (String[] v : variants) for (String wn : new String[]{"P60", "B60"}) for (int s = SENS_FIRST; s < SENS_FIRST + nSeeds; s++) {
            final String[] vv = v; final String w = wn; final long seed = s;
            jobs.add(() -> {
                Params q = p.copy(); String lab = vv[0] + "x" + vv[1];
                if (vv[0].equals("resFrac")) { q.resFrac = Double.parseDouble(vv[1]); lab = "resFrac=" + vv[1]; }
                else if (vv[0].equals("ucrit")) { q.ucrit = Double.parseDouble(vv[1]); lab = "ucrit=" + vv[1]; }
                else if (!vv[0].equals("base")) q.set(vv[0], p.get(vv[0]) * Double.parseDouble(vv[1]));
                Node[] proto = Topology.tiered(Topology.WAN_DEFAULT, 1); Task[] tasks = Workload.byName(w).generate(seed);
                return List.of(row(lab, Simulator.run(proto, tasks, Kind.PROPOSED, q, seed, "tiered", w)));
            });
        }
        execute(jobs, outDir.resolve("sens.csv"));
    }

    /** Random search for the eight parameters on tuning seeds that are disjoint from the evaluation seeds (9001..9005). */
    public static Params tune(Path outDir, int candidates) throws Exception {
        Random r = new Random(12345);
        Params best = new Params(); double bestJ = Double.POSITIVE_INFINITY;
        long[] seeds = {9001, 9002, 9003, 9004, 9005}; String[] wls = {"P60", "B60"};
        Node[] proto = Topology.tiered(Topology.WAN_DEFAULT, 1);
        Task[][][] tasks = new Task[wls.length][seeds.length][];
        for (int a = 0; a < wls.length; a++) for (int b = 0; b < seeds.length; b++) tasks[a][b] = Workload.byName(wls[a]).generate(seeds[b]);
        try (BufferedWriter w = Files.newBufferedWriter(outDir.resolve("tuning_log.csv"))) {
            w.write("cand,J,w1,w2,w3,w4,w5,am,ar,om"); w.newLine();
            for (int c = 0; c <= candidates; c++) {
                Params q = new Params();
                if (c > 0) { // c == 0 evaluates the default parameter vector
                    for (String k : new String[]{"w1", "w2", "w3", "w4", "w5"}) q.set(k, Math.exp(Math.log(0.1) + r.nextDouble() * (Math.log(3.0) - Math.log(0.1))));
                    q.am = Math.exp(Math.log(1) + r.nextDouble() * Math.log(20)); q.ar = r.nextDouble() * 2; q.om = r.nextDouble() * 1.5;
                }
                double J = 0; int cnt = 0;
                for (int a = 0; a < wls.length; a++) for (int b = 0; b < seeds.length; b++) {
                    Simulator.Result x = Simulator.run(proto, tasks[a][b], Kind.PROPOSED, q, seeds[b], "tiered", wls[a]);
                    J += (1 - x.dlRate) + (1 - x.critDlRate) + 0.05 * x.meanResp; cnt++; // objective: overall miss + critical miss + 0.05*mean response
                }
                J /= cnt;
                w.write(String.format(Locale.ROOT, "%d,%.6f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n", c, J, q.w1, q.w2, q.w3, q.w4, q.w5, q.am, q.ar, q.om));
                if (J < bestJ) { bestJ = J; best = q; System.out.printf(Locale.ROOT, "  cand %d: J=%.4f%n", c, J); }
            }
        }
        best.save(outDir.resolve("tuned_params.properties"));
        return best;
    }

    /** Jointly tune the reserved fraction eta (resFrac) and the urgency threshold ucrit on seeds disjoint from both
     *  the final-evaluation seeds (31..) and the sensitivity seeds (1..30). Returns a Params copy with resFrac and
     *  ucrit set to the best grid point, and writes tuned_gate_params.properties + tune_gate_log.csv. */
    public static Params tuneGate(Path outDir, Params base, int scale) throws Exception {
        double[] resFracs = {0.1, 0.2, 0.3, 0.5};
        double[] ucrits = {0.6, 0.8, 0.9};
        long[] seeds = {9006, 9007, 9008, 9009, 9010};
        String[] wls = {"P60", "B60"};
        double bestJ = Double.POSITIVE_INFINITY; double bestRf = 0.3, bestU = 0.8;
        Node[] proto = Topology.tiered(Topology.WAN_DEFAULT, scale);
        try (BufferedWriter w = Files.newBufferedWriter(outDir.resolve("tune_gate_log.csv"))) {
            w.write("resFrac,ucrit,J"); w.newLine();
            for (double rf : resFracs) for (double u : ucrits) {
                double J = 0; int cnt = 0;
                for (String wl : wls) for (long seed : seeds) {
                    Task[] tasks = Workload.byName(wl).generate(seed);
                    Params q = base.copy(); q.resFrac = rf; q.ucrit = u;
                    Simulator.Result r = Simulator.run(proto, tasks, Kind.PROPOSED, q, seed, "tiered", wl);
                    J += (1 - r.dlRate) + (1 - r.critDlRate) + 0.05 * r.meanResp; cnt++;
                }
                J /= cnt;
                w.write(String.format(Locale.ROOT, "%.2f,%.1f,%.6f%n", rf, u, J));
                if (J < bestJ) { bestJ = J; bestRf = rf; bestU = u; System.out.printf(Locale.ROOT, "  gate tune rf=%.2f u=%.1f J=%.6f%n", rf, u, J); }
            }
        }
        Params best = base.copy(); best.resFrac = bestRf; best.ucrit = bestU;
        best.save(outDir.resolve("tuned_gate_params.properties"));
        return best;
    }

    public static void paramTable(Path outDir, Params p) throws IOException {
        StringBuilder sb = new StringBuilder("group,name,value,description\n");
        String[][] t = {{"w1","offloading-latency weight"},{"w2","response-time weight"},{"w3","execution-time weight"},{"w4","energy weight"},
            {"w5","load-penalty weight"},{"am","deadline-miss penalty (alpha_m)"},{"ar","deadline-margin reward (alpha_r)"},{"om","tier-bonus weight (omega)"}};
        for (String[] r : t) sb.append("tuned,").append(r[0]).append(',').append(p.get(r[0])).append(',').append(r[1]).append('\n');
        sb.append("fixed,ucrit,").append(p.ucrit).append(",urgency threshold of the gate\n");
        sb.append("fixed,resFrac,").append(p.resFrac).append(",edge capacity reserved for urgent tasks\n");
        sb.append("fixed,H,").append(p.H).append(",load-penalty horizon (s)\n");
        sb.append("fixed,nw/ih,").append(p.nw).append('/').append(p.ih).append(",wolves/GWO iterations (hybrid)\n");
        sb.append("fixed,nh/iw,").append(p.nh).append('/').append(p.iw).append(",whales/WOA iterations (hybrid)\n");
        sb.append("fixed,popSolo/itSolo,").append(p.popSolo).append('/').append(p.itSolo).append(",population/iterations of standalone GWO, WOA, PSO\n");
        sb.append("fixed,Ptx,").append(Eval.PTX).append(",transmission power (W)\n");
        sb.append("fixed,duration,").append(Workload.DURATION).append(",arrival window (s)\n");
        Files.writeString(outDir.resolve("parameters.csv"), sb.toString());
        StringBuilder nd = new StringBuilder("tier,count,mips,ram_mb,bw_mbps,oneway_delay_s,busy_power_w\n");
        for (Node v : Topology.tiered(Topology.WAN_DEFAULT, 1)) nd.append(v.tier).append(",1,").append(v.mu).append(',').append(v.ram).append(',').append(v.bw).append(',').append(v.delta).append(',').append(v.power).append('\n');
        Files.writeString(outDir.resolve("topology.csv"), nd.toString());
    }
}
