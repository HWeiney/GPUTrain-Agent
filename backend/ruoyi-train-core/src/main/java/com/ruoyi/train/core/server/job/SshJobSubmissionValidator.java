package com.ruoyi.train.core.server.job;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.job.service.JobSubmission;
import com.ruoyi.train.core.server.service.ServerDeviceQueryService.DeviceView;
import com.ruoyi.train.core.server.service.ServerInventoryService.ServerDetail;
import com.ruoyi.train.core.server.service.ServerInventoryService.ServerAccountView;

@Component
public final class SshJobSubmissionValidator
{
    public record Prepared(String unitName, Set<String> gpuUuids) { }
    private final SystemdCommandBuilder commands = new SystemdCommandBuilder();

    public Prepared validate(JobSubmission submission, ServerDetail detail, List<DeviceView> devices,
            Instant now, UUID jobUuid)
    {
        if (submission == null || submission.serverId() == null || submission.accountId() == null
                || detail == null || devices == null || now == null || jobUuid == null)
            throw invalid("SERVER_NOT_TRAINABLE");
        var server = detail.server();
        ServerAccountView account = detail.accounts().stream()
                .filter(candidate -> submission.accountId() != null && submission.accountId().equals(candidate.accountId()))
                .findFirst().orElseThrow(() -> invalid("SERVER_ACCOUNT_UNAVAILABLE"));
        if (!submission.serverId().equals(server.serverId()) || !submission.serverId().equals(account.serverId())
                || submission.ownerUserId() != account.ownerUserId() || !server.enabled()
                || !Set.of("ONLINE", "DEGRADED").contains(server.status()) || !account.hasCredential()
                || account.allowedRoot() == null || !server.capabilities().getOrDefault("ssh", false)
                || !server.capabilities().getOrDefault("gpu", false)
                || !server.capabilities().getOrDefault("systemdUser", false)
                || !server.capabilities().getOrDefault("persistentUserManager", false)
                || !server.capabilities().getOrDefault("appendLogs", false))
            throw invalid("SERVER_NOT_TRAINABLE");

        Set<String> selected = Set.copyOf(submission.allowedUuids());
        if (selected.size() != 3 || submission.gpuCount() != 3) throw invalid("GPU_SELECTION_INVALID");
        Set<String> fresh = devices.stream().filter(device -> device.serverId().equals(submission.serverId())
                && device.accountId().equals(submission.accountId()) && device.sampledAt() != null
                && !device.sampledAt().isBefore(now.minusSeconds(30))
                && !device.sampledAt().isAfter(now.plusSeconds(60))
                && device.totalMemoryMiB() - device.usedMemoryMiB() >= submission.minFreeMemoryMiB()
                && !Set.of("UNOBSERVABLE", "MIG_UNSUPPORTED").contains(device.identityState()))
                .map(DeviceView::gpuUuid).collect(Collectors.toSet());
        if (!fresh.containsAll(selected)) throw invalid("GPU_SNAPSHOT_STALE");
        return new Prepared(commands.identity(1, jobUuid, server.serverId(), account.accountId(), account.allowedRoot()).unitName(), selected);
    }

    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
