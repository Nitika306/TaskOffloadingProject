package fogsim;

import org.cloudbus.cloudsim.core.CloudSimTags;

/** Event tags used by the iFogSim2 dispatcher. Each constant is a {@link CloudSimTags}. */
enum IFogEvents implements CloudSimTags {
    TAG_DISPATCH(91001),
    TAG_ARRIVE(91002),
    TAG_DONE(91003);

    private final int value;

    IFogEvents(int value) {
        this.value = value;
    }

    int value() {
        return value;
    }
}
