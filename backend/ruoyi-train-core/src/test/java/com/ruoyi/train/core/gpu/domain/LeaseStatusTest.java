package com.ruoyi.train.core.gpu.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseStatusTest
{
    @Test
    void onlyActiveHoldsTheGpu()
    {
        assertTrue(LeaseStatus.ACTIVE.isActive());
        assertFalse(LeaseStatus.RELEASED.isActive());
        assertFalse(LeaseStatus.EXPIRED_QUARANTINED.isActive());
    }

    @Test
    void expiredAndReleasedAreNotReusableDirectly()
    {
        // 过期不是空闲：EXPIRED_QUARANTINED 必须先重新探测，不视作可分配占用态
        assertFalse(LeaseStatus.EXPIRED_QUARANTINED.isActive());
        assertFalse(LeaseStatus.RELEASED.isActive());
    }
}
