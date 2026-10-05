package com.ruoyi.train.core.gpu.domain;

public enum LeaseStatus
{
    ACTIVE, RELEASED, EXPIRED_QUARANTINED;

    public boolean isActive()
    {
        return this == ACTIVE;
    }
}
