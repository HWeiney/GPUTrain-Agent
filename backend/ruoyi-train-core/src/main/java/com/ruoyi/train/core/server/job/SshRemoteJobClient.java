package com.ruoyi.train.core.server.job;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.ssh.SshGateway;
import com.ruoyi.train.core.server.ssh.SshGateway.CommandResult;
import com.ruoyi.train.core.server.ssh.SshGateway.ErrorCode;
import com.ruoyi.train.core.server.ssh.SshGateway.SshException;
import com.ruoyi.train.core.server.ssh.SshGateway.SshSession;
import org.springframework.stereotype.Component;

@Component
public final class SshRemoteJobClient
{
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_STATE_BYTES = 16 * 1024;
    private static final int MAX_SCRIPT_BYTES = 64 * 1024;
    private final SshGateway gateway;
    private final SystemdCommandBuilder commands;

    public SshRemoteJobClient(SshGateway gateway)
    {
        this(gateway, new SystemdCommandBuilder());
    }

    SshRemoteJobClient(SshGateway gateway, SystemdCommandBuilder commands)
    {
        this.gateway = Objects.requireNonNull(gateway);
        this.commands = Objects.requireNonNull(commands);
    }

    public SystemdUnitState inspect(ServerRecord server, ServerAccountRecord account,
            SystemdCommandBuilder.RemoteJobIdentity job)
    {
        validate(server, account, job);
        return gateway.withVerifiedSession(server, account, session -> inspect(session, job));
    }

    public SystemdUnitState start(ServerRecord server, ServerAccountRecord account,
            SystemdCommandBuilder.RemoteJobIdentity job,
            SystemdCommandBuilder.FixedTrainingProfile profile, List<String> gpuUuids)
    {
        validate(server, account, job);
        String script = commands.script(job, profile, gpuUuids);
        return gateway.withVerifiedSession(server, account, session -> {
            SystemdUnitState before = inspect(session, job);
            if (before.loaded()) return before;
            session.uploadPrivateFile(job.allowedRoot(), job.workingDirectory(), "run.sh",
                    script.getBytes(StandardCharsets.UTF_8), MAX_SCRIPT_BYTES);
            CommandResult started = session.exec(commands.startCommand(job), COMMAND_TIMEOUT, MAX_STATE_BYTES);
            SystemdUnitState after = inspect(session, job);
            if (after.loaded() || started.exitCode() == 0) return after;
            throw new SshException(ErrorCode.COMMAND_FAILED);
        });
    }

    public SystemdUnitState stop(ServerRecord server, ServerAccountRecord account,
            SystemdCommandBuilder.RemoteJobIdentity job)
    {
        validate(server, account, job);
        return gateway.withVerifiedSession(server, account, session -> {
            SystemdUnitState before = inspect(session, job);
            if (!before.loaded() || !before.active()) return before;
            CommandResult stopped = session.exec(commands.stopCommand(job), COMMAND_TIMEOUT, MAX_STATE_BYTES);
            SystemdUnitState after = inspect(session, job);
            if (stopped.exitCode() != 0 && after.active()) throw new SshException(ErrorCode.COMMAND_FAILED);
            return after;
        });
    }

    private SystemdUnitState inspect(SshSession session, SystemdCommandBuilder.RemoteJobIdentity job)
    {
        CommandResult response = session.exec(commands.inspectCommand(job), COMMAND_TIMEOUT, MAX_STATE_BYTES);
        if (response.exitCode() != 0) throw new SshException(ErrorCode.COMMAND_FAILED);
        return SystemdUnitState.parse(response.stdout());
    }

    private static void validate(ServerRecord server, ServerAccountRecord account,
            SystemdCommandBuilder.RemoteJobIdentity job)
    {
        if (server == null || account == null || job == null || !server.enabled() || !account.enabled()
                || !Objects.equals(server.serverId(), job.serverId())
                || !Objects.equals(account.serverId(), server.serverId())
                || !Objects.equals(account.accountId(), job.accountId())) throw new IllegalArgumentException("SERVER_ACCOUNT_UNAVAILABLE");
    }
}
