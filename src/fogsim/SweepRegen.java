package fogsim;

import fogsim.Node;
import fogsim.Task;
import fogsim.Workload;
import fogsim.Scheduler.Kind;
import fogsim.Topology;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public final class SweepRegen {
    public static void main(String[] a) throws Exception {
        Path outDir = Paths.get("results_sweeps_regen");
        Files.createDirectories(outDir);
        PrintWriter pw = new PrintWriter(new FileWriter(outDir.resolve("sweeps.csv").toString()));
        pw.println("sweep,param,seed,wl,sched,n_tasks,critDlRate,dlRate,meanResp,p99Resp,decisionUs,energyPerTask");
        
        String wl = "B60";
        Kind sched = Kind.PROPOSED;
        long[] seeds = {1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30};
        
        // --- ETA SWEEP ---
        System.out.println("=== ETA SWEEP ===");
        double[] etas = {0.1, 0.2, 0.3, 0.5};
        for (double eta : etas) {
            for (long seed : seeds) {
                runOne(pw, "eta", String.format("%.1f", eta), seed, wl, sched, eta, null, null, null, outDir);
            }
            System.out.println("  eta=" + eta + " done");
        }
        
        // --- WAN-DELAY SWEEP (cloud one-way delta) ---
        System.out.println("=== WAN DELAY SWEEP ===");
        double[] clouds = {0.020, 0.040, 0.060, 0.080, 0.100, 0.120, 0.150, 0.200, 0.250, 0.300, 0.400};
        for (double cd : clouds) {
            Node[] topo = makeTiered(cd);
            for (long seed : seeds) {
                runOne(pw, "wan", String.format("%.0fms", cd*1000), seed, wl, sched, 0.3, topo, null, null, outDir);
            }
            System.out.println("  cloudDelta=" + cd + " done");
        }
        
        // --- WEIGHT SWEEP (scale each weight by 0.5 or 1.5) ---
        System.out.println("=== WEIGHT SWEEP ===");
        String[] wnames = {"w1","w2","w3","w4","w5","am","ar","om"};
        double[] scales = {0.5, 1.5};
        double[] defaults = {1.0, 1.0, 0.5, 0.2, 0.5, 5.0, 0.5, 0.3};
        for (int wi = 0; wi < 8; wi++) {
            for (double sc : scales) {
                for (long seed : seeds) {
                    runOne(pw, "weight", wnames[wi] + "x" + sc, seed, wl, sched, 0.3, null, wnames[wi], sc*defaults[wi], outDir);
                }
            }
            System.out.println("  weight " + wnames[wi] + " done");
        }
        
        pw.close();
        System.out.println("Wrote results_sweeps_regen/sweeps.csv");
    }
    
    static Node[] makeTiered(double cloudDelta) {
        Node[] n = new Node[8];
        int k = 0;
        for (int i = 0; i < 4; i++) { n[k] = new Node(k, Node.Tier.EDGE, 4000, 512, 50, 0.002, 87.53); k++; }
        for (int i = 0; i < 3; i++) { n[k] = new Node(k, Node.Tier.REGIONAL, 8000, 4096, 200, 0.015, 107.339); k++; }
        n[k] = new Node(k, Node.Tier.CLOUD, 40000, 65536, 400, cloudDelta, 1648.0);
        return n;
    }
    
    static void runOne(PrintWriter pw, String sweep, String param, long seed, String wl, Kind sched,
                       double eta, Node[] topo, String wname, Double wval, Path outDir) throws Exception {
        Task[] tasks = Workload.byName(wl).generate(seed);
        Params p = new Params();
        p.w1=1.0; p.w2=1.0; p.w3=0.5; p.w4=0.2; p.w5=0.5; p.am=5.0; p.ar=0.5; p.om=0.3;
        p.ucrit=0.8; p.resFrac=eta; p.H=1.0;
        if (wname != null && wval != null) p.set(wname, wval);
        Node[] proto = (topo != null) ? topo : Topology.tiered(Topology.WAN_DEFAULT, 1);
        Scheduler s = new Scheduler(sched);
        Simulator.Result r = Simulator.run(proto, tasks, sched, p, seed, "tiered", wl);
        pw.printf("%s,%s,%d,%s,%s,%d,%.6f,%.6f,%.6f,%.6f,%.4f,%.4f%n",
            sweep, param, seed, wl, sched.name(), r.n, r.critDlRate, r.dlRate, r.meanResp, r.p99Resp, r.decisionUs, r.energyPerTask);
    }
}
