package fogsim;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** All tunable and fixed constants of the blueprint (Section VI of the paper). */
public final class Params implements Cloneable {
    // eight tunable fitness parameters
    public double w1 = 1.0, w2 = 1.0, w3 = 0.5, w4 = 0.2, w5 = 0.5, am = 5.0, ar = 0.5, om = 0.3;
    // fixed system/algorithm constants (not tuned)
    public double ucrit = 0.8;     // urgency threshold of the gate
    public double resFrac = 0.30;  // reserved edge capacity for urgent tasks
    public double H = 1.0;         // load-penalty horizon, s
    public int nw = 15, ih = 20;   // hybrid: wolves, GWO iterations
    public int nh = 15, iw = 20;   // hybrid: whales, WOA iterations
    public int popSolo = 15, itSolo = 40; // standalone swarms: same 600-evaluation budget

    public static final String[] TUNABLE = {"w1", "w2", "w3", "w4", "w5", "am", "ar", "om"};

    public double get(String k) {
        switch (k) {
            case "w1": return w1; case "w2": return w2; case "w3": return w3; case "w4": return w4;
            case "w5": return w5; case "am": return am; case "ar": return ar; case "om": return om;
            default: throw new IllegalArgumentException(k);
        }
    }
    public void set(String k, double v) {
        switch (k) {
            case "w1": w1 = v; break; case "w2": w2 = v; break; case "w3": w3 = v; break; case "w4": w4 = v; break;
            case "w5": w5 = v; break; case "am": am = v; break; case "ar": ar = v; break; case "om": om = v; break;
            default: throw new IllegalArgumentException(k);
        }
    }

    public Params copy() {
        try { return (Params) super.clone(); } catch (CloneNotSupportedException e) { throw new RuntimeException(e); }
    }

    public void save(Path p) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String k : TUNABLE) sb.append(k).append('=').append(get(k)).append('\n');
        Files.writeString(p, sb.toString());
    }

    public static Params loadOrDefault(Path p) throws IOException {
        Params q = new Params();
        if (Files.exists(p)) {
            for (String line : Files.readAllLines(p)) {
                String[] kv = line.trim().split("=");
                if (kv.length == 2) q.set(kv[0], Double.parseDouble(kv[1]));
            }
        }
        return q;
    }
}
