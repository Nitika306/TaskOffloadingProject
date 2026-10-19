package fogsim;

import fogsim.Node;
import fogsim.Task;
import fogsim.Workload;
import fogsim.Scheduler;
import fogsim.Scheduler.Kind;
import fogsim.Topology;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public final class LaneError {
    public static void main(String[] a) throws Exception {
        Path outDir = Paths.get("results_lane_error");
        Files.createDirectories(outDir);
        PrintWriter pw = new PrintWriter(new FileWriter(outDir.resolve("lane_error.csv").toString()));
        pw.println("seed,wl,eta,n_tasks,est_minus_act_mean_ms,est_minus_act_p99_ms,reserved_idle_while_main_busy_pct");
        
        double[] etas = {0.3, 0.5, 0.7};
        long[] seeds = {31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50};
        String[] wls = {"B60"};
        Node[] proto = Topology.tiered(Topology.WAN_DEFAULT, 1);
        
        for (double eta : etas) for (String wl : wls) for (long seed : seeds) {
            Task[] tasks = Workload.byName(wl).generate(seed);
            Params p = new Params();
            p.w1=1.0; p.w2=1.0; p.w3=0.5; p.w4=0.2; p.w5=0.5; p.am=5.0; p.ar=0.5; p.om=0.3;
            p.ucrit=0.8; p.resFrac=eta; p.H=1.0;
            
            Node[] nodes = Topology.fresh(proto);
            for (Node v : nodes) if (v.tier==Node.Tier.EDGE) v.resFrac=eta;
            Scheduler s = new Scheduler(Kind.PROPOSED);
            
            double[] errors = new double[tasks.length];
            int nNonUrgent=0, nResIdleMainBusy=0;
            
            for (int i=0;i<tasks.length;i++) {
                Task t=tasks[i]; double now=t.t;
                Node v=s.assign(t,nodes,now,new Random(seed*31L+Kind.PROPOSED.ordinal()),p);
                boolean gateOn = t.u>=p.ucrit;
                int lane=(s.gate()&&gateOn&&v.resFrac>0)?1:0;
                // ESTIMATE
                double toff=2*v.delta+(t.din+t.dout)/v.bw;
                double rate=v.rate(lane);
                double texe=t.L/rate;
                double tque=Math.max(0.0,v.freeAt[lane]-now);
                double est=toff+tque+texe;
                // EXECUTE
                double arrive=now+v.delta+t.din/v.bw;
                double start=Math.max(arrive,v.freeAt[lane]);
                double finish=start+texe;
                v.freeAt[lane]=finish;
                double back=finish+v.delta+t.dout/v.bw;
                double act=back-now;
                errors[i]=(est-act)*1000.0;
                // Lane underutilization: non-urgent task, reserved lane idle, main lane busy
                if(!gateOn) {
                    nNonUrgent++;
                    if(v.freeAt[1]<=now && v.freeAt[0]>now) nResIdleMainBusy++;
                }
            }
            Arrays.sort(errors);
            double meanErr=Arrays.stream(errors).average().getAsDouble();
            double p99Err=errors[(int)Math.min(errors.length-1,Math.floor(0.99*errors.length))];
            double idlePct=nNonUrgent>0?100.0*nResIdleMainBusy/nNonUrgent:0;
            pw.printf("%d,%s,%.2f,%d,%.4f,%.4f,%.4f%n",seed,wl,eta,tasks.length,meanErr,p99Err,idlePct);
            System.out.printf("eta=%.2f seed=%d n=%d meanErr_ms=%.4f p99Err_ms=%.4f idlePct=%.4f%n",eta,seed,tasks.length,meanErr,p99Err,idlePct);
        }
        pw.close();
        System.out.println("Wrote results_lane_error/lane_error.csv");
    }
}
