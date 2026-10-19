
package fogsim;

import java.util.*;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.fog.entities.Actuator;
import org.fog.entities.FogDevice;
import org.fog.entities.Sensor;
import org.fog.placement.Controller;
import org.fog.utils.FogEvents;
import org.fog.utils.FogUtils;

final class IFogDispatcher extends Controller {

    enum Queue { FIFO, PS }

    static final double TS = 1000.0;
    static final double T0 = 1.0;

    static final class Lane {
        final FogDevice dev;
        final String module;
        boolean busy;
        final ArrayDeque<IFogTaskTuple> queue = new ArrayDeque<>();

        Lane(FogDevice dev, String module) {
            this.dev = dev;
            this.module = module;
        }
    }

    final Node[] nodes;
    final Task[] tasks;
    final Scheduler sched;
    final Params p;
    final Random rng;
    final Lane[][] lanes;
    final Queue qm;
    final String appId;
    final int brokerId;

    final double[] resp;
    int completed, gated, dispatched, arrived;
    double offSum, energy, lastFinish;
    long decNs;
    final int[] tierCnt = new int[3];
    final int[][] submittedByLane;
    final int[][] completedByLane;

    IFogDispatcher(
            List<FogDevice> devices,
            Node[] nodes,
            Task[] tasks,
            Scheduler sched,
            Params p,
            Random rng,
            Lane[][] lanes,
            Queue qm,
            String appId,
            int brokerId) {

        super("dispatcher", devices,
                new ArrayList<Sensor>(), new ArrayList<Actuator>());

        this.nodes = nodes;
        this.tasks = tasks;
        this.sched = sched;
        this.p = p;
        this.rng = rng;
        this.lanes = lanes;
        this.qm = qm;
        this.appId = appId;
        this.brokerId = brokerId;

        this.resp = new double[tasks.length];
        this.submittedByLane = new int[nodes.length][2];
        this.completedByLane = new int[nodes.length][2];
    }

    @Override
    public void startEntity() {
        super.startEntity();

        for (int i = 0; i < tasks.length; i++) {
            send(getId(),
                    (T0 + tasks[i].t) * TS,
                    IFogEvents.TAG_DISPATCH,
                    i);
        }
    }

    @Override
    public void processEvent(SimEvent ev) {
        CloudSimTags tag = ev.getTag();

        if (tag == IFogEvents.TAG_DISPATCH) {
            dispatch((Integer) ev.getData());
        } else if (tag == IFogEvents.TAG_ARRIVE) {
            arrive((IFogTaskTuple) ev.getData());
        } else if (tag == IFogEvents.TAG_DONE) {
            done((IFogTaskTuple) ev.getData());
        } else if (tag == FogEvents.TUPLE_ACK) {
            // Consume the stock FogDevice acknowledgement.
        } else if (tag == FogEvents.STOP_SIMULATION) {
            // Avoid Controller's stock STOP_SIMULATION handling.
        } else {
            super.processEvent(ev);
        }
    }

    private void dispatch(int i) {
        Task t = tasks[i];

        long started = System.nanoTime();
        Node v = sched.assign(t, nodes, t.t, rng, p);
        decNs += System.nanoTime() - started;

        dispatched++;

        boolean gateOn = t.u >= p.ucrit;
        if (gateOn) {
            gated++;
        }

        int lane = (sched.gate() && gateOn && v.resFrac > 0) ? 1 : 0;

        double exe = t.L / v.rate(lane);
        double transit = v.delta + t.din / v.bw;

        double arriveTime = t.t + transit;
        double start = Math.max(arriveTime, v.freeAt[lane]);
        v.freeAt[lane] = start + exe;

        offSum += 2 * v.delta + (t.din + t.dout) / v.bw;
        energy += v.power * exe
                + Eval.PTX * (t.din + t.dout) / v.bw;

        v.busyMI += t.L;
        v.assigned++;
        tierCnt[v.tier.ordinal()]++;

        Lane selectedLane = lanes[v.id][lane];

        IFogTaskTuple tp = new IFogTaskTuple(
                appId,
                FogUtils.generateTupleId(),
                Math.max(1L, Math.round(t.L * TS)),
                i,
                v.id,
                lane);

        tp.setUserId(brokerId);
        tp.setTupleType("TASK");
        tp.setSrcModuleName("GATEWAY");
        tp.setDestModuleName(selectedLane.module);
        tp.setDestinationDeviceId(selectedLane.dev.getId());
        tp.setActualTupleId(tp.getCloudletId());

        tp.listener = x -> CloudSim.send(
                getId(), getId(), 0.0, IFogEvents.TAG_DONE, x);

        send(getId(), transit * TS, IFogEvents.TAG_ARRIVE, tp);
    }

    private void arrive(IFogTaskTuple tp) {
        arrived++;

        Lane lane = lanes[tp.nodeId][tp.lane];

        if (qm == Queue.PS || !lane.busy) {
            lane.busy = true;
            submitToDevice(tp);
        } else {
            lane.queue.addLast(tp);
        }
    }

    private void submitToDevice(IFogTaskTuple tp) {
        submittedByLane[tp.nodeId][tp.lane]++;

        Lane lane = lanes[tp.nodeId][tp.lane];
        sendNow(lane.dev.getId(), FogEvents.TUPLE_ARRIVAL, tp);
    }

    private void done(IFogTaskTuple tp) {
        if (tp.taskIdx < 0 || tp.taskIdx >= tasks.length) {
            System.err.println(
                    "Ignoring completion with invalid task index: "
                    + tp.taskIdx);
            return;
        }

        Task t = tasks[tp.taskIdx];

        if (tp.doneAt < 0 || resp[tp.taskIdx] != 0.0) {
            // Do not count a duplicate completion.
            return;
        }

        Node v = nodes[tp.nodeId];

        double back = (tp.doneAt / TS - T0)
                + v.delta + t.dout / v.bw;

        resp[tp.taskIdx] = back - t.t;
        lastFinish = Math.max(lastFinish, back);

        completed++;
        completedByLane[tp.nodeId][tp.lane]++;

        if (qm == Queue.FIFO) {
            Lane lane = lanes[tp.nodeId][tp.lane];
            IFogTaskTuple next = lane.queue.pollFirst();

            if (next != null) {
                submitToDevice(next);
            } else {
                lane.busy = false;
            }
        }

        if (completed == tasks.length) {
            CloudSim.terminateSimulation(CloudSim.clock() + 1.0);
        }
    }

    void printDiagnostics() {
        System.out.println();
        System.out.println(
                "========== iFogSim CALIBRATION DIAGNOSTICS ==========");
        System.out.println("Simulation clock: " + CloudSim.clock());
        System.out.println("Expected tasks: " + tasks.length);
        System.out.println("Dispatched: " + dispatched);
        System.out.println("Gateway arrivals: " + arrived);

        int submittedTotal = 0;
        int completedTotal = 0;
        int queuedTotal = 0;
        int busyTotal = 0;

        for (int n = 0; n < lanes.length; n++) {
            for (int l = 0; l < lanes[n].length; l++) {
                Lane lane = lanes[n][l];

                if (lane == null) {
                    continue;
                }

                submittedTotal += submittedByLane[n][l];
                completedTotal += completedByLane[n][l];
                queuedTotal += lane.queue.size();

                if (lane.busy) {
                    busyTotal++;
                }
            }
        }

        System.out.println("Device submitted: " + submittedTotal);
        System.out.println("Completed: " + completed);
        System.out.println("Per-lane counts:");

        for (int n = 0; n < lanes.length; n++) {
            for (int l = 0; l < lanes[n].length; l++) {
                Lane lane = lanes[n][l];

                if (lane == null) {
                    continue;
                }

                System.out.println(
                        "node=" + n
                        + " lane=" + l
                        + " module=" + lane.module
                        + " submitted=" + submittedByLane[n][l]
                        + " completed=" + completedByLane[n][l]
                        + " queued=" + lane.queue.size()
                        + " busy=" + lane.busy);
            }
        }

        System.out.println("Total submitted: " + submittedTotal);
        System.out.println("Total completed by lane: " + completedTotal);
        System.out.println("Total queued: " + queuedTotal);
        System.out.println("Busy lanes: " + busyTotal);
        System.out.println(
                "=====================================================");
    }
}
