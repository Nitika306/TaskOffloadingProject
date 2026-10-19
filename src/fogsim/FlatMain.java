package fogsim;

import fogsim.Node;
import fogsim.Task;
import fogsim.Workload;
import fogsim.Scheduler.Kind;
import fogsim.Topology;
import java.nio.file.*;
import java.util.*;

public final class FlatMain {
    public static void main(String[] a) throws Exception {
        Path outDir = Paths.get("results_flat");
        Files.createDirectories(outDir);
        Params p = Params.loadOrDefault(Paths.get("params/tuned_params.properties"));
        List<Experiments.Job> jobs = new ArrayList<>();
        for (Workload wl : Workload.ALL) for (int s = 31; s <= 60; s++) {
            final Workload w = wl; final long seed = s;
            jobs.add(() -> {
                Node[] proto = Topology.flat(1);
                Task[] tasks = w.generate(seed);
                List<String> rows = new ArrayList<>();
                for (Kind k : Kind.values()) rows.add(Experiments.row("flat", Simulator.run(proto, tasks, k, p, seed, "flat", w.name)));
                return rows;
            });
        }
        Experiments.execute(jobs, outDir.resolve("main.csv"));
        System.out.println("Wrote results_flat/main.csv with " + jobs.size() + " jobs");
    }
}
