package com.ruoyi.train.core.server.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.ruoyi.train.runner.protocol.gpu.GpuSnapshot;

/** Facts from one account's pinned SSH session, independent of Runner state. */
public record ServerProbeResult(Long serverId, Long accountId, Instant sampledAt,
        double load1, long memoryTotalKiB, long memoryAvailableKiB,
        long diskTotalKiB, long diskAvailableKiB, List<GpuSnapshot> gpus,
        String driverVersion, Map<String, Boolean> capabilities, String status, String errorCode)
{
    public ServerProbeResult { gpus = List.copyOf(gpus); capabilities = Map.copyOf(capabilities); }
    public static ServerProbeResult failure(Long serverId, Long accountId, Instant now, String status, String code)
    {
        return new ServerProbeResult(serverId, accountId, now, 0, 0, 0, 0, 0,
            List.of(), null, Map.of("ssh", false, "gpu", false, "systemdUser", false,
                    "persistentUserManager", false, "appendLogs", false), status, code);
    }
}
