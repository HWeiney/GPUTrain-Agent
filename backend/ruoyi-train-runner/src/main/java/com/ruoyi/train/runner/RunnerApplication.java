package com.ruoyi.train.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.ruoyi.train.runner.client.RunnerControlClient;
import com.ruoyi.train.runner.config.RunnerConfig;
import com.ruoyi.train.runner.process.*;
import com.ruoyi.train.runner.protocol.*;

/** Single command loop plus independent heartbeats; report retries retain the leased attempt. */
public final class RunnerApplication implements AutoCloseable
{
    private final RunnerConfig config;
    private final RunnerControlClient client;
    private final ProcessPolicy policy;
    private final ProcessStateStore stateStore;
    private final ProcessSupervisor supervisor;
    private final Map<UUID, RunnerCommandLease> active = new LinkedHashMap<>();
    private final Deque<PendingReport> pending = new ArrayDeque<>();
    private final Set<UUID> reconciliationRequired = new HashSet<>();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
    private final RetryBackoff controlBackoff = new RetryBackoff();
    private final RetryBackoff heartbeatBackoff = new RetryBackoff();
    private final Object communicationGate = new Object();
    private volatile boolean closed;
    private volatile RuntimeException terminalFailure;
    private volatile IOException terminalTransportFailure;

    public RunnerApplication(RunnerConfig config) throws IOException
    {
        this.config = Objects.requireNonNull(config);
        client = new RunnerControlClient(config);
        policy = new ProcessPolicy(config);
        stateStore = new ProcessStateStore(config.allowedRoot());
        supervisor = new ProcessSupervisor(stateStore, ProcessLookup.system());
    }

    public static void main(String[] args) throws Exception
    {
        if (args.length != 1)
        {
            System.err.println("Usage: java -jar ruoyi-train-runner.jar /absolute/path/runner.properties");
            System.exit(2);
        }
        try (RunnerApplication application = new RunnerApplication(RunnerConfig.load(Path.of(args[0]))))
        {
            Runtime.getRuntime().addShutdownHook(new Thread(application::close, "runner-shutdown"));
            application.run();
        }
    }

    public void run() throws IOException, InterruptedException
    {
        byte[] secret = config.readSecret(); Arrays.fill(secret, (byte) 0);
        List<RunnerProcessSnapshot> snapshots = new ArrayList<>();
        for (ManagedProcess saved : stateStore.loadAll())
        {
            ProcessRecovery recovery = supervisor.recover(saved);
            snapshots.add(new RunnerProcessSnapshot(saved.commandId(), saved.pid(), saved.processStartedAt(),
                    saved.executable(), saved.argumentsSha256(), recovery.status(), saved.exitCode(), recovery.errorCode()));
        }
        while (!closed && !Thread.currentThread().isInterrupted())
        {
            try { client.reconcile(snapshots); controlBackoff.reset(); break; }
            catch (RunnerProtocolException failure)
            {
                if (failure.code() != RunnerErrorCode.RUNNER_TEMPORARILY_UNAVAILABLE) throw failure;
                Thread.sleep(controlBackoff.nextDelayMillis());
            }
            catch (IOException failure)
            {
                if (!retryable(failure)) throw failure;
                Thread.sleep(controlBackoff.nextDelayMillis());
            }
        }
        scheduleHeartbeat(0);
        while (!closed && !Thread.currentThread().isInterrupted())
        {
            try { tick(); controlBackoff.reset(); }
            catch (RunnerProtocolException failure)
            {
                if (failure.code() != RunnerErrorCode.RUNNER_TEMPORARILY_UNAVAILABLE) throw failure;
                Thread.sleep(controlBackoff.nextDelayMillis());
            }
            catch (IOException failure)
            {
                if (!retryable(failure)) throw failure;
                Thread.sleep(controlBackoff.nextDelayMillis());
            }
        }
    }

    public void tick() throws IOException, InterruptedException
    {
        requireCommunicationAllowed();
        try { tickOnce(); }
        catch (RunnerProtocolException failure)
        {
            if (failure.code() != RunnerErrorCode.RUNNER_TEMPORARILY_UNAVAILABLE) terminalFailure = failure;
            throw failure;
        }
        catch (IOException failure)
        {
            if (!retryable(failure)) terminalTransportFailure = failure;
            throw failure;
        }
    }

    private void tickOnce() throws IOException, InterruptedException
    {
        flushReports();
        for (var iterator = active.entrySet().iterator(); iterator.hasNext(); )
        {
            var entry = iterator.next();
            RunnerProcessSnapshot snapshot = supervisor.observe(entry.getKey());
            if (snapshot.status() != RunnerProcessStatus.RUNNING)
            {
                queue(entry.getValue(), RunnerCommandStatus.RUNNING, RunnerCommandStatus.valueOf(snapshot.status().name()), snapshot, null);
                iterator.remove();
            }
        }
        flushReports();
        Optional<RunnerCommandLease> next = client.poll();
        synchronized (communicationGate)
        {
            requireCommunicationAllowed();
            if (next.isPresent()) execute(next.get());
        }
        flushReports();
    }

    private void execute(RunnerCommandLease lease) throws InterruptedException
    {
        if (lease.leaseUntil().isBefore(Instant.now()) || reconciliationRequired.contains(lease.commandId())) return;
        try
        {
            if (lease.type() == RunnerCommandType.START_PROCESS)
            {
                RunnerProcessSnapshot snapshot = supervisor.start(lease.commandId(), policy.validate(lease.process()));
                queue(lease, RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING, snapshot, null);
                active.put(lease.commandId(), lease);
            }
            else
            {
                RunnerProcessSnapshot snapshot = supervisor.stop(lease.targetCommandId());
                queue(lease, RunnerCommandStatus.LEASED, RunnerCommandStatus.RUNNING, snapshot, null);
                queue(lease, RunnerCommandStatus.RUNNING,
                    snapshot.status() == RunnerProcessStatus.RUNNING ? RunnerCommandStatus.FAILED : RunnerCommandStatus.SUCCEEDED, snapshot, null);
            }
        }
        catch (ProcessPolicyException failure)
        { queue(lease, RunnerCommandStatus.LEASED, RunnerCommandStatus.REJECTED, null, failure.code()); }
        catch (IOException | IllegalArgumentException failure)
        { queue(lease, RunnerCommandStatus.LEASED, RunnerCommandStatus.REJECTED, null, null); }
    }

    private void queue(RunnerCommandLease lease, RunnerCommandStatus previous, RunnerCommandStatus status,
            RunnerProcessSnapshot snapshot, RunnerErrorCode error)
    {
        pending.add(new PendingReport(lease.commandId(), new RunnerCommandReport(previous, status,
            snapshot == null ? null : snapshot.pid(), snapshot == null ? null : snapshot.processStartedAt(),
            snapshot == null ? null : snapshot.exitCode(), error, null, lease.attempt())));
    }

    private void flushReports() throws IOException, InterruptedException
    {
        while (!pending.isEmpty())
        {
            requireCommunicationAllowed();
            PendingReport report = pending.getFirst();
            try { client.report(report.commandId(), report.report()); pending.removeFirst(); }
            catch (RunnerProtocolException failure)
            {
                boolean requiresReconciliation = failure.code() == RunnerErrorCode.COMMAND_STATUS_CONFLICT
                        || failure.code() == RunnerErrorCode.COMMAND_NOT_FOUND;
                if (!requiresReconciliation && failure.code() != RunnerErrorCode.COMMAND_LEASE_EXPIRED) throw failure;
                pending.removeIf(p -> p.commandId().equals(report.commandId()));
                active.remove(report.commandId());
                if (requiresReconciliation)
                {
                    reconciliationRequired.add(report.commandId());
                    System.err.println("Runner command requires reconciliation; automatic reporting suspended.");
                }
                // Only an expired attempt can be adopted on redelivery; conflicts require reconciliation.
            }
        }
    }

    private void scheduleHeartbeat(long delayMillis)
    {
        if (closed || terminalFailure != null || terminalTransportFailure != null) return;
        try { heartbeats.schedule(this::heartbeat, delayMillis, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException stopped) { if (!closed) throw stopped; }
    }

    private void heartbeat()
    {
        long nextDelay;
        try
        {
            requireCommunicationAllowed();
            client.heartbeat(new RunnerHeartbeat(config.runnerId(), System.getProperty("user.name"), Instant.now()));
            heartbeatBackoff.reset();
            nextDelay = config.heartbeatInterval().toMillis();
        }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        catch (RunnerProtocolException failure)
        {
            if (failure.code() != RunnerErrorCode.RUNNER_TEMPORARILY_UNAVAILABLE)
            {
                synchronized (communicationGate) { terminalFailure = failure; }
                return;
            }
            nextDelay = heartbeatBackoff.nextDelayMillis();
        }
        catch (IOException failure)
        {
            if (!retryable(failure))
            {
                synchronized (communicationGate) { terminalTransportFailure = failure; }
                return;
            }
            nextDelay = heartbeatBackoff.nextDelayMillis();
        }
        catch (RuntimeException failure) { terminalFailure = failure; return; }
        scheduleHeartbeat(nextDelay);
    }

    private void requireCommunicationAllowed() throws IOException
    {
        if (terminalFailure != null) throw terminalFailure;
        if (terminalTransportFailure != null) throw terminalTransportFailure;
    }

    private static boolean retryable(IOException failure)
    { return !(failure instanceof RunnerControlClient.HttpFailure http) || http.retryable(); }

    boolean communicationTerminated()
    { return terminalFailure != null || terminalTransportFailure != null; }

    @Override public void close()
    {
        closed = true;
        heartbeats.shutdownNow();
        supervisor.close();
    }
    private record PendingReport(UUID commandId, RunnerCommandReport report) { }
}
