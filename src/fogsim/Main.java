package fogsim;

import java.nio.file.*;

/** Usage: java -cp out fogsim.Main <tune|main|wan|sens|all> [seeds=30] [outDir=results] */
public final class Main {
    public static void main(String[] a) throws Exception {
        String mode = a.length > 0 ? a[0] : "all";
        int seeds = a.length > 1 ? Integer.parseInt(a[1]) : 30;
        Path out = Paths.get(a.length > 2 ? a[2] : "results"); Files.createDirectories(out);
        Path tuned = out.resolve("tuned_params.properties");
        long t0 = System.currentTimeMillis();
        if (mode.equals("tune") || mode.equals("all")) { System.out.println("Tuning..."); Experiments.tune(out, 40); }
        Params p = Params.loadOrDefault(tuned);
        Experiments.paramTable(out, p);
        if (mode.equals("main") || mode.equals("all")) { System.out.println("Main experiment..."); Experiments.main_(out, p, seeds); }
        if (mode.equals("wan") || mode.equals("all")) { System.out.println("WAN sweep..."); Experiments.wan(out, p, seeds); }
        if (mode.equals("sens") || mode.equals("all")) { System.out.println("Sensitivity..."); Experiments.sens(out, p, seeds); }
        System.out.printf("Done in %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
    }
}
