package com.ruoyi.train.core.server.service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.server.domain.*;
import com.ruoyi.train.core.server.mapper.*;
import com.ruoyi.train.core.server.ssh.*;

/** Internal probing entry point. Web callers must authorize the account before calling probe. */
@Service
@EnableScheduling
public class ServerProbeService implements AutoCloseable
{
    private final ServerMapper servers;
    private final ServerAccountMapper accounts;
    private final ServerSnapshotMapper snapshots;
    private final ServerGpuDeviceMapper devices;
    private final ServerGpuSnapshotMapper gpuSnapshots;
    private final ServerProbe collector;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final ObjectMapper json = new ObjectMapper();
    private final Semaphore slots = new Semaphore(SshGateway.MAX_SESSIONS);
    private final ConcurrentMap<Long, ProbeState> states = new ConcurrentHashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(0, SshGateway.MAX_SESSIONS,
        30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "server-probe"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private long scanAfter;

    @Autowired
    public ServerProbeService(ServerMapper servers, ServerAccountMapper accounts, ServerSnapshotMapper snapshots,
            ServerGpuDeviceMapper devices, ServerGpuSnapshotMapper gpuSnapshots, ServerProbe collector,
            PlatformTransactionManager manager)
    { this(servers, accounts, snapshots, devices, gpuSnapshots, collector, manager, Clock.systemUTC()); }

    public ServerProbeService(ServerMapper servers, ServerAccountMapper accounts, ServerSnapshotMapper snapshots,
            ServerGpuDeviceMapper devices, ServerGpuSnapshotMapper gpuSnapshots, ServerProbe collector,
            PlatformTransactionManager manager, Clock clock)
    {
        this.servers = servers; this.accounts = accounts; this.snapshots = snapshots;
        this.devices = devices; this.gpuSnapshots = gpuSnapshots; this.collector = collector;
        this.transaction = new TransactionTemplate(manager); this.clock = clock;
    }

    public ServerProbeResult probe(Long serverId, Long accountId)
    {
        ServerRecord server = servers.findById(serverId);
        ServerAccountRecord account = accounts.findById(accountId);
        if (server == null || account == null || !server.enabled() || !account.enabled()
            || !account.hasCredential() || !serverId.equals(account.serverId()))
            throw new ServiceException("Server account unavailable");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ProbeState state = states.computeIfAbsent(accountId, key -> new ProbeState());
        synchronized (state)
        {
            Instant persistedNext = account.lastProbeAt() == null ? Instant.MIN : account.lastProbeAt().plusSeconds(10);
            if (state.running || now.isBefore(state.next) || now.isBefore(persistedNext))
                return ServerProbeResult.failure(serverId, accountId, now, "UNKNOWN", "PROBE_THROTTLED");
            if (!slots.tryAcquire())
                return ServerProbeResult.failure(serverId, accountId, now, "UNKNOWN", "CONCURRENCY_LIMIT");
            state.running = true;
            state.next = now.plusSeconds(10);
        }
        boolean successful = false;
        try
        {
            ServerProbeResult result;
            try
            {
                ServerAccountRecord credentials = accounts.findCredentialsByAuthorizedAccountId(accountId);
                result = collector.collect(server, credentials, now);
                persist(result);
                successful = true;
                return result;
            }
            catch (SshGateway.SshException failure)
            { result = ServerProbeResult.failure(serverId, accountId, now, status(failure.code()), failure.code().name()); }
            catch (IllegalArgumentException corrupt)
            { result = ServerProbeResult.failure(serverId, accountId, now, "DEGRADED", "CORRUPT_OUTPUT"); }
            accounts.updateProbeOutcome(accountId, result.status(), result.errorCode(), now, false);
            return result;
        }
        finally
        {
            synchronized (state)
            {
                state.failures = successful ? 0 : Math.min(6, state.failures + 1);
                long delay = successful ? 10 : Math.min(300, 10L << (state.failures - 1));
                // Completion-based delay also prevents immediately repeating a slow timed-out probe.
                state.next = clock.instant().plusSeconds(delay);
                state.running = false;
            }
            slots.release();
        }
    }

    private void persist(ServerProbeResult result)
    {
        String capabilities = encode(result.capabilities(), 4096);
        String facts = encode(Map.of("load1", result.load1(), "memoryTotalKiB", result.memoryTotalKiB(),
            "memoryAvailableKiB", result.memoryAvailableKiB(), "diskTotalKiB", result.diskTotalKiB(),
            "diskAvailableKiB", result.diskAvailableKiB()), 65536);
        // Serialize before the transaction: corrupt/oversized process evidence never becomes an empty list.
        List<ServerGpuSnapshotRecord> gpuRows = result.gpus().stream().map(gpu -> new ServerGpuSnapshotRecord(null,
            result.serverId(), result.accountId(), gpu.uuid(), gpu.visibleIndex(), gpu.totalMemoryMiB(),
            gpu.usedMemoryMiB(), gpu.utilizationPercent(), gpu.migMode(), result.driverVersion(),
            encode(gpu.computeProcesses(), 65536), result.sampledAt(), result.sampledAt())).toList();
        transaction.executeWithoutResult(tx -> {
            snapshots.insert(new ServerSnapshotRecord(null, result.serverId(), result.accountId(), result.sampledAt(),
                result.status(), result.errorCode(), capabilities, facts, result.sampledAt()));
            for (int i = 0; i < result.gpus().size(); i++)
            {
                var gpu = result.gpus().get(i);
                var state = SshNvidiaSmiParser.state(gpu);
                devices.upsert(new ServerGpuDeviceRecord(null, result.serverId(), gpu.uuid(), gpu.name(),
                    state.name(), state.isSchedulable(), result.sampledAt(), result.sampledAt()));
                gpuSnapshots.insert(gpuRows.get(i));
            }
            accounts.updateProbeOutcome(result.accountId(), result.status(), result.errorCode(), result.sampledAt(), true);
        });
    }

    private String encode(Object value, int limit)
    {
        try
        {
            String encoded = json.writeValueAsString(value);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > limit) throw new SshGateway.SshException(SshGateway.ErrorCode.OUTPUT_LIMIT);
            return encoded;
        }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("CORRUPT_OUTPUT"); }
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void probeEnabledAccounts()
    {
        List<ServerAccountRecord> page = accounts.listEnabledForProbe(scanAfter, 100);
        if (page.isEmpty()) { scanAfter = 0; return; }
        for (ServerAccountRecord account : page)
        {
            try
            {
                workers.execute(() -> {
                    try { probe(account.serverId(), account.accountId()); }
                    catch (RuntimeException failure) { /* Retry on a later cadence; never log credential/remote messages. */ }
                });
            }
            catch (RejectedExecutionException busy) { break; }
            scanAfter = account.accountId();
        }
    }

    private static String status(SshGateway.ErrorCode code)
    {
        return switch (code) {
            case HOST_KEY_CHANGED, HOST_KEY_UNCONFIRMED -> "HOST_KEY_CHANGED";
            case AUTH_FAILED, AUTH_TIMEOUT, CREDENTIAL_INVALID -> "AUTH_FAILED";
            case CONNECT_FAILED, CONNECT_TIMEOUT -> "OFFLINE";
            default -> "DEGRADED";
        };
    }
    private static final class ProbeState { boolean running; int failures; Instant next = Instant.MIN; }
    @Override @PreDestroy public void close() { workers.shutdownNow(); }
}
