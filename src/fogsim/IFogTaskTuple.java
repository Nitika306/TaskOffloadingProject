package fogsim;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.entities.Tuple;

/**
 * One offloaded task, represented as an iFogSim2 {@link Tuple} (which is a CloudSim Cloudlet).
 *
 * The device's cloudlet scheduler marks the tuple SUCCESS at the instant its execution ends. We capture exactly that
 * instant by overriding updateStatus (the method CloudletScheduler.cloudletFinish actually calls), so the measured
 * completion time comes from iFogSim2's own execution engine.
 */
final class IFogTaskTuple extends Tuple {
    interface DoneListener { void done(IFogTaskTuple t); }

    final int taskIdx, nodeId, lane;
    double doneAt = -1;
    DoneListener listener;

    IFogTaskTuple(String appId, int tupleId, long lengthUnits, int taskIdx, int nodeId, int lane) {
        super(appId, tupleId, Tuple.UP, lengthUnits, 1, 1, 1,
              new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());
        this.taskIdx = taskIdx; this.nodeId = nodeId; this.lane = lane;
    }

    @Override
    public boolean updateStatus(Cloudlet.CloudletStatus status) {
        boolean result = super.updateStatus(status);

        if (status == Cloudlet.CloudletStatus.SUCCESS && doneAt < 0) {
            doneAt = CloudSim.clock();

            if (listener != null) {
                listener.done(this);
            }
        }

        return result;
    }
}
