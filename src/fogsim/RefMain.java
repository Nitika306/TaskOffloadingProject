package fogsim;

import fogsim.Scheduler.Kind;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Pure Java (no iFogSim2): writes the arrival-order-FIFO reference results in the same CSV format as IFogMain, so the
 * iFogSim2 run can be compared row by row. Usage: java -cp out fogsim.RefMain --topo tiered --wl B60 [--seeds 31-60]
 * [--sched ALL] [--wan 0.1] [--params params/tuned_params.properties] [--out results_ref/ref_tiered_B60.csv]
 */
public final class RefMain {
    public static void main(String[] a) throws Exception {
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i + 1 < a.length; i += 2) o.put(a[i].replaceFirst("^--", ""), a[i + 1]);
        Params p = Params.loadOrDefault(Paths.get(o.getOrDefault("params", "params/tuned_params.properties")));
        String topo = o.getOrDefault("topo", "tiered"), wl = o.getOrDefault("wl", "P60");
        double wan = Double.parseDouble(o.getOrDefault("wan", String.valueOf(Topology.WAN_DEFAULT)));
        String[] sr = o.getOrDefault("seeds", "31-60").split("-");
        long s0 = Long.parseLong(sr[0]), s1 = Long.parseLong(sr[sr.length - 1]);
        List<Kind> kinds = new ArrayList<>();
        String sp = o.getOrDefault("sched", "ALL");
        if (sp.equals("ALL")) kinds.addAll(Arrays.asList(Kind.values())); else for (String k : sp.split(",")) kinds.add(Kind.valueOf(k));
        Path out = Paths.get(o.getOrDefault("out", "results_ref/ref_" + topo + "_" + wl + ".csv"));
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Node[] proto = Experiments.topo(topo, wan, 1);
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write(Experiments.HEADER); w.newLine();
            for (long seed = s0; seed <= s1; seed++) {
                Task[] tasks = Workload.byName(wl).generate(seed);
                for (Kind k : kinds) { w.write(Experiments.row("ref_fifo", ReferenceSim.run(proto, tasks, k, p, seed, topo, wl))); w.newLine(); }
            }
        }
    }
}
