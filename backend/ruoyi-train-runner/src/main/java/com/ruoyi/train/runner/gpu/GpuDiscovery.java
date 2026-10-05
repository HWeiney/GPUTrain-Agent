package com.ruoyi.train.runner.gpu;

import com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryReport;

/** Discovers the user-visible NVIDIA GPUs on this host. */
public interface GpuDiscovery
{
    GpuDiscoveryReport discover();
}
