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
    private final ProcessSupervisor supervisor = new ProcessSupervisor();
    private final Map<UUID, RunnerCommandLease> active = new LinkedHashMap<>();
    private final Deque<PendingReport> pending = new ArrayDeque<>();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;

    public RunnerApplication(RunnerConfig config)
    {
        this.config = Objects.requireNonNull(config);
        client = new RunnerControlClient(config);
        policy = new ProcessPolicy(config);
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
        heartbeats.scheduleWithFixedDelay(() -> {
            try { client.heartbeat(new RunnerHeartbeat(config.runnerId(), System.getProperty("user.name"), Instant.now())); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException | RuntimeException failure) { System.err.println("Runner heartbeat failed; retrying."); }
        }, 0, config.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        while (!closed && !Thread.currentThread().isInterrupted())
        {
            try { tick(); }
            catch (IOException | RunnerProtocolException failure)
            {
                System.err.println("Runner control request failed; retrying.");
                Thread.sleep(1000);
            }
        }
    }

    public void tick() throws IOException, InterruptedException
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
        if (next.isPresent()) execute(next.get());
        flushReports();
    }

    private void execute(RunnerCommandLease lease) throws InterruptedException
    {
        if (lease.leaseUntil().isBefore(Instant.now())) return;
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
            PendingReport report = pending.getFirst();
            try { client.report(report.commandId(), report.report()); pending.removeFirst(); }
            catch (RunnerProtocolException failure)
            {
                if (failure.code() != RunnerErrorCode.COMMAND_LEASE_EXPIRED) throw failure;
                pending.removeIf(p -> p.commandId().equals(report.commandId()));
                active.remove(report.commandId());
                // Redelivery will adopt the existing local process using the new lease attempt.
            }
        }
    }

    @Override public void close()
    {
        closed = true;
        heartbeats.shutdownNow();
        supervisor.close();
    }
    private record PendingReport(UUID commandId, RunnerCommandReport report) { }
}
