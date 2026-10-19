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

public final class CloudDelaySweep {
    public static void main(String[] a) throws Exception {
        Path outDir = Paths.get("results_cloud_sweep");
        Files.createDirectories(outDir);
        PrintWriter pw = new PrintWriter(new FileWriter(outDir.resolve("cloud_sweep.csv").toString()));
        pw.println("cloudDeltaMs,wl,sched,n_tasks,critDlRate_feasible,critDlRate_infeasible,dlRate_feasible,dlRate_infeasible,n_feasible,n_infeasible");
        
        double[] cloudDeltas = {0.020, 0.040, 0.060, 0.080, 0.100, 0.120, 0.150, 0.200, 0.250, 0.300, 0.400};
        // Feasibility bound: 2*cloudDelta < D. Health deadlines 0.05-0.12s.
        // At cloudDelta=0.05s: bound=0.10s -> health tasks with D>=0.10s feasible
        // At cloudDelta=0.10s: bound=0.20s -> all health tasks feasible (max D=0.12s)
        String[] wls = {"P60", "B60"};
        Kind[] scheds = {Kind.PROPOSED, Kind.ABL_GATECLOUD, Kind.GREEDY, Kind.ABL_NOGATE};
        
        Node[] proto = Topology.tiered(0.0, 1); // base, will replace cloud delta
        
        for (double cd : cloudDeltas) {
            // Rebuild topology with this cloud delay
            Node[] n = new Node[4*1+3*1+1];
            int k=0;
            for(int i=0;i<4;i++){ n[k]=new Node(k,Node.Tier.EDGE,4000,512,50,0.002,87.53); k++; }
            for(int i=0;i<3;i++){ n[k]=new Node(k,Node.Tier.REGIONAL,8000,4096,200,0.015,107.339); k++; }
            n[k]=new Node(k,Node.Tier.CLOUD,40000,65536,400,cd,1648.0);
            Node[] topo=n;
            
            for(String wl:wls) for(Kind sched:scheds) for(int seed=31;seed<=40;seed++) {
                Task[] tasks=Workload.byName(wl).generate(seed);
                Params p=new Params();
                p.w1=1.0;p.w2=1.0;p.w3=0.5;p.w4=0.2;p.w5=0.5;p.am=5.0;p.ar=0.5;p.om=0.3;
                p.ucrit=0.8;p.resFrac=0.3;p.H=1.0;
                Node[] nodes=Topology.fresh(topo);
                for(Node v:nodes) if(v.tier==Node.Tier.EDGE) v.resFrac=p.resFrac;
                Scheduler s=new Scheduler(sched);
                
                int nFeas=0,nInfeas=0; int cokFeas=0,cokInfeas=0; int cFeas=0,cInfeas=0;
                int okFeas=0,okInfeas=0; int nFeasAll=0,nInfeasAll=0;
                
                for(Task t:tasks) {
                    double now=t.t;
                    Node v=s.assign(t,nodes,now,new Random(seed*31L+sched.ordinal()),p);
                    boolean gateOn=t.u>=p.ucrit;
                    int lane=(s.gate()&&gateOn&&v.resFrac>0)?1:0;
                    double rate=v.rate(lane); double texe=t.L/rate;
                    double arrive=now+v.delta+t.din/v.bw;
                    double start=Math.max(arrive,v.freeAt[lane]);
                    double finish=start+texe;
                    v.freeAt[lane]=finish;
                    double back=finish+v.delta+t.dout/v.bw;
                    double resp=back-now;
                    boolean met=resp<=t.D;
                    boolean isCrit=(t.cls==Task.Cls.HEALTH);
                    
                    // Feasibility: can cloud physically meet this deadline? 2*cloudDelta < D
                    boolean cloudFeasible = 2.0*cd < t.D;
                    // For non-cloud placements, task feasible if min possible delay on assigned node < D
                    double minDelay = 2*v.delta + (t.din+t.dout)/v.bw + t.L/v.mu;
                    boolean taskFeasible = minDelay < t.D;
                    // A task is "network-feasible" if cloud can meet its deadline; otherwise violations are unavoidable
                    boolean feasible = cloudFeasible;
                    
                    // Use cloudFeasible: if cloud can't meet deadline, violation is unavoidable network bound
                    if(feasible) {
                        nFeasAll++; if(met) okFeas++; if(isCrit){cFeas++; if(met)cokFeas++;}
                    } else {
                        nInfeasAll++; if(met) okInfeas++; if(isCrit){cInfeas++; if(met)cokInfeas++;}
                    }
                }
                double cdRateFeas = cFeas>0?(double)cokFeas/cFeas:Double.NaN;
                double cdRateInfeas = cInfeas>0?(double)cokInfeas/cInfeas:Double.NaN;
                double dlRateFeas = nFeasAll>0?(double)okFeas/nFeasAll:Double.NaN;
                double dlRateInfeas = nInfeasAll>0?(double)okInfeas/nInfeasAll:Double.NaN;
                pw.printf("%.0f,%s,%s,%d,%.4f,%.4f,%.4f,%.4f,%d,%d%n",cd*1000,wl,sched.name(),tasks.length,
                    cdRateFeas,cdRateInfeas,dlRateFeas,dlRateInfeas,nFeasAll,nInfeasAll);
            }
        }
        pw.close();
        System.out.println("Wrote results_cloud_sweep/cloud_sweep.csv");
    }
}
