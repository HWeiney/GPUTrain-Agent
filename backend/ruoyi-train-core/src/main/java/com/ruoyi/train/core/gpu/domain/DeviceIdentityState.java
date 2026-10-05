package com.ruoyi.train.core.gpu.domain;

public enum DeviceIdentityState
{
    CONFIRMED, IDENTITY_AMBIGUOUS, MIG_UNSUPPORTED, UNOBSERVABLE;

    public boolean isSchedulable()
    {
        return this == CONFIRMED;
    }
}
