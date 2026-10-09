package com.ruoyi.train.core.server.service;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.gpu.domain.DeviceIdentityState;
import com.ruoyi.train.core.server.domain.*;
import com.ruoyi.train.core.server.mapper.*;

/** Owner/account-scoped inventory facts. These views do not grant GPU leases. */
@Service
public class ServerDeviceQueryService
{
    public record DeviceView(Long serverId, Long accountId, String gpuUuid, String name,
        String identityState, boolean schedulable, Instant sampledAt, long totalMemoryMiB,
        long usedMemoryMiB, int utilizationPercent, String migMode, int externalProcessCount) { }
    private final ServerMapper servers;
    private final ServerAccountMapper accounts;
    private final ServerSnapshotMapper snapshots;
    private final ServerGpuDeviceMapper devices;
    private final ServerGpuSnapshotMapper gpuSnapshots;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public ServerDeviceQueryService(ServerMapper servers, ServerAccountMapper accounts, ServerSnapshotMapper snapshots,
        ServerGpuDeviceMapper devices, ServerGpuSnapshotMapper gpuSnapshots)
    { this(servers, accounts, snapshots, devices, gpuSnapshots, Clock.systemUTC()); }

    public ServerDeviceQueryService(ServerMapper servers, ServerAccountMapper accounts, ServerSnapshotMapper snapshots,
        ServerGpuDeviceMapper devices, ServerGpuSnapshotMapper gpuSnapshots, Clock clock)
    { this.servers = servers; this.accounts = accounts; this.snapshots = snapshots; this.devices = devices; this.gpuSnapshots = gpuSnapshots; this.clock = clock; }

    public List<DeviceView> list(Long serverId, Long accountId, Long ownerUserId)
    {
        ServerAccountRecord account = accounts.findById(accountId);
        if (ownerUserId == null || account == null || !ownerUserId.equals(account.ownerUserId())
            || !Objects.equals(serverId, account.serverId())) throw new ServiceException("Server account unavailable");
        ServerRecord server = servers.findById(serverId);
        ServerSnapshotRecord host = snapshots.findLatest(serverId, accountId);
        List<DeviceView> result = new ArrayList<>();
        for (ServerGpuDeviceRecord device : devices.listByServer(serverId))
        {
            ServerGpuSnapshotRecord gpu = gpuSnapshots.findLatest(serverId, accountId, device.gpuUuid());
            if (gpu == null) continue; // Another account's observation is not this user's evidence.
            int external = externalCount(gpu.computeProcessesJson());
            boolean current = server != null && server.enabled() && account.enabled() && host != null && gpu.sampledAt().equals(host.sampledAt())
                && Objects.equals(account.lastProbeAt(), account.lastSuccessAt())
                && Objects.equals(account.lastSuccessAt(), host.sampledAt())
                && Set.of("ONLINE", "DEGRADED").contains(account.status())
                && !gpu.sampledAt().isBefore(clock.instant().minusSeconds(30))
                && !gpu.sampledAt().isAfter(clock.instant().plusSeconds(60)) && hasGpu(host.capabilitiesJson());
            DeviceIdentityState state = !current || external < 0 ? DeviceIdentityState.UNOBSERVABLE
                : gpu.gpuUuid().startsWith("MIG-") || "ENABLED".equals(gpu.migMode()) ? DeviceIdentityState.MIG_UNSUPPORTED
                : !Set.of("DISABLED", "N/A", "[N/A]").contains(gpu.migMode()) ? DeviceIdentityState.UNOBSERVABLE
                : external > 0 ? DeviceIdentityState.EXTERNAL_OCCUPIED : DeviceIdentityState.OBSERVED;
            result.add(new DeviceView(serverId, accountId, gpu.gpuUuid(), device.name(), state.name(),
                state.isSchedulable(), gpu.sampledAt(), gpu.totalMemoryMiB(), gpu.usedMemoryMiB(),
                gpu.utilizationPercent(), gpu.migMode(), Math.max(0, external)));
        }
        return List.copyOf(result);
    }
    private boolean hasGpu(String capabilities)
    {
        try { return json.readTree(capabilities).path("gpu").asBoolean(false); }
        catch (Exception invalid) { return false; }
    }
    private int externalCount(String processes)
    {
        try
        {
            if (processes == null || processes.length() > 65536) return -1;
            JsonNode rows = json.readTree(processes);
            if (!rows.isArray() || rows.size() > 512) return -1;
            Set<Long> pids = new HashSet<>();
            for (JsonNode row : rows)
            {
                if (!row.path("pid").canConvertToLong() || row.path("pid").asLong() <= 0
                    || !row.path("processName").isTextual() || !row.path("usedMemoryMiB").canConvertToLong()
                    || row.path("usedMemoryMiB").asLong() < 0) return -1;
                pids.add(row.path("pid").asLong());
            }
            return pids.size();
        }
        catch (Exception invalid) { return -1; }
    }
}
