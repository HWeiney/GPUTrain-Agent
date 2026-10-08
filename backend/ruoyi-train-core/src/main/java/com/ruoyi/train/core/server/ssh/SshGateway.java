package com.ruoyi.train.core.server.ssh;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;

/** Internal control-plane transport. Callers must authorize account access first. */
public interface SshGateway
{
    Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    Duration DEFAULT_AUTH_TIMEOUT = Duration.ofSeconds(10);
    Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(15);
    int MAX_OUTPUT_BYTES = 1024 * 1024;
    int MAX_SESSIONS = 8;

    HostKeyPreview preview(String host, int port, Duration timeout);
    <T> T withVerifiedSession(ServerRecord server, ServerAccountRecord account, Function<SshSession, T> operation);

    record HostKeyPreview(String algorithm, String sha256) { }

    interface SshSession extends AutoCloseable
    {
        CommandResult exec(List<String> fixedCommand, Duration timeout, int maxBytes);
        @Override void close();
    }

    record CommandResult(int exitCode, String stdout, String stderr)
    {
        @Override public String toString() { return "CommandResult[exitCode=" + exitCode + "]"; }
    }

    enum ErrorCode
    {
        MASTER_KEY_NOT_CONFIGURED, CREDENTIAL_INVALID, HOST_KEY_UNCONFIRMED, HOST_KEY_CHANGED,
        CONNECT_TIMEOUT, CONNECT_FAILED, AUTH_TIMEOUT, AUTH_FAILED, ACCOUNT_INVALID,
        COMMAND_NOT_ALLOWED, COMMAND_TIMEOUT, COMMAND_FAILED, OUTPUT_LIMIT, INVALID_LIMIT, CONCURRENCY_LIMIT
    }

    /** Deliberately excludes library messages/causes, which can contain remote data. */
    final class SshException extends RuntimeException
    {
        private final ErrorCode code;
        public SshException(ErrorCode code) { super(code.name()); this.code = code; }
        public ErrorCode code() { return code; }
    }
}
