package com.ruoyi.train.core.gpu.domain;

/**
 * Identity/safety state of a GPU as derived from an observed snapshot.
 * Only OBSERVED devices are candidates for scheduling; everything else fails closed.
 */
public enum DeviceIdentityState
{
    /** Not yet observed or no longer visible in the latest report. */
    UNOBSERVABLE,
    /** Same GPU observed under conflicting namespaces; physical uniqueness unconfirmed. */
    IDENTITY_AMBIGUOUS,
    /** MIG device/enabled; phase three does not schedule MIG. */
    MIG_UNSUPPORTED,
    /** A non-GPUTrain process is using the device. */
    EXTERNAL_OCCUPIED,
    /** Observed with a clean identity and no unconfirmed external process. */
    OBSERVED,
    /** Expired/uncertain lease; re-probing required before any reuse. */
    QUARANTINED;

    public boolean isSchedulable()
    {
        return this == OBSERVED;
    }
}
