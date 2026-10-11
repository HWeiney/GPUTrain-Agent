package com.ruoyi.train.core.server.proposal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.mapper.JobMapper;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.mapper.ServerAccountMapper;
import com.ruoyi.train.core.server.mapper.ServerMapper;
import com.ruoyi.train.core.server.proposal.domain.CommandProposalRecord;
import com.ruoyi.train.core.server.proposal.mapper.ProposalMapper;
import com.ruoyi.train.core.server.ssh.SshGateway;

/**
 * 通道 B 测试共用 fake：内存版 CAS 语义（与 ProposalMapper SQL 状态机一致）、
 * 脱敏/带机密双视图语义（与 ServerAccountMapper publicColumns 一致）与动作装配。
 */
final class ProposalTestFakes
{
    private ProposalTestFakes() { }

    /** 与 CommandActionConfig 相同的装配（真 JobService + fake JobMapper）。 */
    static CommandActionRegistry registry(FakeJobMapper jobs)
    {
        CommandActionConfig config = new CommandActionConfig();
        JobService jobService = new JobService(jobs, new ObjectMapper(), null, null, null);
        return new CommandActionRegistry(List.of(config.probeLoadavg(), config.probeMeminfo(),
            config.probeDisk(), config.probeGpu(), config.probeGpuProcesses(), config.probeSystemd(),
            config.probeCapability(), config.jobInspect(jobService), config.jobStop(jobService)));
    }

    static ServerRecord server(long serverId, boolean enabled)
    {
        return new ServerRecord(serverId, "node-" + serverId, "127.0.0.1", 22,
                "SHA256:" + "A".repeat(43), enabled, Instant.now(), Instant.now());
    }

    static ServerAccountRecord account(long accountId, long serverId, long ownerUserId,
            String credentialCiphertext, boolean hasCredential)
    {
        return new ServerAccountRecord(accountId, serverId, ownerUserId, "alice", "PASSWORD",
                credentialCiphertext, null, hasCredential, "/home/alice/training", "{}", true,
                "ONLINE", null, null, null, Instant.now(), Instant.now());
    }

    static JobRecord sshJob(long jobId, long ownerUserId, long serverId, long accountId, String jobUuid)
    {
        String unit = "gputrain-" + jobUuid.toLowerCase(Locale.ROOT) + ".service";
        return new JobRecord(jobId, jobUuid, ownerUserId, null, 3, 1000, "[]", "[]", "{}",
                "RUNNING", "req-" + jobId, null, null, Instant.now(), Instant.now(),
                "SSH", serverId, accountId, unit, "/home/alice/training/project");
    }

    /** 内存版提案存储：mark* 仅在状态匹配时生效（模拟 SQL WHERE 条件更新）。 */
    static final class FakeProposalMapper implements ProposalMapper
    {
        final Map<String, CommandProposalRecord> rows = new LinkedHashMap<>();
        final List<String> failedCodes = new ArrayList<>();

        public int insert(CommandProposalRecord record) { rows.put(record.proposalId(), record); return 1; }

        public CommandProposalRecord findById(String proposalId) { return rows.get(proposalId); }

        public List<CommandProposalRecord> listForActor(Long actorUserId)
        {
            return rows.values().stream().filter(row -> actorUserId.equals(row.actorUserId())).toList();
        }

        public List<CommandProposalRecord> listAll() { return List.copyOf(rows.values()); }

        public int markExecuted(String proposalId, Instant decidedAt)
        {
            CommandProposalRecord current = rows.get(proposalId);
            if (current == null || !"PENDING".equals(current.status())) return 0;
            rows.put(proposalId, evolved(current, "EXECUTED", current.exitCode(), current.stdout(),
                    current.stderr(), current.errorCode(), decidedAt, current.executedAt()));
            return 1;
        }

        public int markRejected(String proposalId, Instant decidedAt)
        {
            CommandProposalRecord current = rows.get(proposalId);
            if (current == null || !"PENDING".equals(current.status())) return 0;
            rows.put(proposalId, evolved(current, "REJECTED", current.exitCode(), current.stdout(),
                    current.stderr(), current.errorCode(), decidedAt, current.executedAt()));
            return 1;
        }

        public int attachResult(String proposalId, int exitCode, String stdout, String stderr, Instant executedAt)
        {
            CommandProposalRecord current = rows.get(proposalId);
            if (current == null || !"EXECUTED".equals(current.status()) || current.exitCode() != null) return 0;
            rows.put(proposalId, evolved(current, current.status(), exitCode, stdout, stderr,
                    current.errorCode(), current.decidedAt(), executedAt));
            return 1;
        }

        public int markFailed(String proposalId, String errorCode, Instant executedAt)
        {
            CommandProposalRecord current = rows.get(proposalId);
            if (current == null || !"EXECUTED".equals(current.status()) || current.exitCode() != null) return 0;
            failedCodes.add(errorCode);
            rows.put(proposalId, evolved(current, "FAILED", null, null, null, errorCode,
                    current.decidedAt(), executedAt));
            return 1;
        }

        private static CommandProposalRecord evolved(CommandProposalRecord record, String status,
                Integer exitCode, String stdout, String stderr, String errorCode,
                Instant decidedAt, Instant executedAt)
        {
            return new CommandProposalRecord(record.proposalId(), record.actorUserId(), record.serverId(),
                    record.accountId(), record.source(), record.rationale(), record.action(), record.argsJson(),
                    record.renderedCommand(), status, exitCode, stdout, stderr, errorCode,
                    record.createdAt(), decidedAt, executedAt);
        }
    }

    static final class FakeServerMapper implements ServerMapper
    {
        ServerRecord record;

        public int insert(ServerRecord server) { return 0; }
        public ServerRecord findById(Long serverId)
        { return record != null && record.serverId().equals(serverId) ? record : null; }
        public List<ServerRecord> listForOwner(Long ownerUserId, boolean isAdmin) { return List.of(); }
        public int setEnabled(Long serverId, boolean enabled, Instant updatedAt) { return 0; }
    }

    /** 模拟真实 publicColumns 语义：findById 无 ciphertext，仅可见性标志。 */
    static final class FakeAccountMapper implements ServerAccountMapper
    {
        ServerAccountRecord record;

        public int insert(ServerAccountRecord account) { return 0; }

        public ServerAccountRecord findById(Long accountId)
        {
            if (record == null || !record.accountId().equals(accountId)) return null;
            return new ServerAccountRecord(record.accountId(), record.serverId(), record.ownerUserId(),
                    record.linuxUsername(), record.authType(), null, null, record.hasCredential(),
                    record.allowedRoot(), record.profileJson(), record.enabled(), record.status(),
                    record.errorCode(), record.lastProbeAt(), record.lastSuccessAt(), record.createdAt(),
                    record.updatedAt());
        }

        public List<ServerAccountRecord> listForOwner(Long serverId, Long ownerUserId) { return List.of(); }

        public ServerAccountRecord findCredentialsByAuthorizedAccountId(Long authorizedAccountId)
        { return record != null && record.accountId().equals(authorizedAccountId) ? record : null; }

        public List<ServerAccountRecord> listEnabledForProbe(Long afterAccountId, int limit) { return List.of(); }

        public int updateCredentials(Long authorizedAccountId, String credentialCiphertext,
                String passphraseCiphertext, Instant updatedAt) { return 0; }

        public int updateProbeOutcome(Long accountId, String status, String errorCode,
                Instant sampledAt, boolean successful) { return 0; }
    }

    static final class FakeJobMapper implements JobMapper
    {
        JobRecord record;

        public int insert(JobRecord job) { return 1; }
        public JobRecord findById(long jobId)
        { return record != null && record.jobId() != null && record.jobId().longValue() == jobId
                ? record : null; }
        public JobRecord findByRequestKey(String requestKey) { return null; }
        public List<JobRecord> listDueSshJobs(Instant cutoff, int limit) { return List.of(); }
        public List<JobRecord> listSshToStart(int limit) { return List.of(); }
        public List<JobRecord> listSshForReconciliation(int limit) { return List.of(); }
        public List<JobRecord> listSshJobsForLogs(Instant terminalCutoff, int limit) { return List.of(); }
        public List<JobRecord> listByOwner(Long ownerUserId, boolean isAdmin, int limit, Long beforeJobId)
        { return List.of(); }
        public int countActive(Long ownerUserId, boolean isAdmin) { return 0; }
        public int compareAndSetStatus(long jobId, String expectedStatus, String newStatus, Instant updatedAt)
        { return 0; }
    }

    static final class FakeGateway implements SshGateway
    {
        final FakeSession session = new FakeSession();

        @Override public HostKeyPreview preview(String host, int port, Duration timeout)
        { throw new UnsupportedOperationException(); }

        @Override public <T> T withVerifiedSession(ServerRecord server, ServerAccountRecord account,
                Function<SshSession, T> operation)
        {
            if (session.failure != null) throw session.failure;
            return operation.apply(session);
        }
    }

    static final class FakeSession implements SshGateway.SshSession
    {
        final List<List<String>> executed = new ArrayList<>();
        SshGateway.CommandResult result = new SshGateway.CommandResult(0, "probe-output", "");
        SshGateway.SshException failure;

        @Override public SshGateway.CommandResult exec(List<String> command, Duration timeout, int maxBytes)
        {
            executed.add(command);
            return result;
        }

        @Override public void close() { }
    }
}
