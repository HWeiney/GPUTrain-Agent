package com.ruoyi.train.core.server.proposal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.server.proposal.domain.CommandProposalRecord;
import com.ruoyi.train.core.server.proposal.ProposalTestFakes.FakeAccountMapper;
import com.ruoyi.train.core.server.proposal.ProposalTestFakes.FakeGateway;
import com.ruoyi.train.core.server.proposal.ProposalTestFakes.FakeJobMapper;
import com.ruoyi.train.core.server.proposal.ProposalTestFakes.FakeProposalMapper;
import com.ruoyi.train.core.server.proposal.ProposalTestFakes.FakeServerMapper;
import com.ruoyi.train.core.server.ssh.SshGateway.CommandResult;
import com.ruoyi.train.core.server.ssh.SshGateway.ErrorCode;
import com.ruoyi.train.core.server.ssh.SshGateway.SshException;

class ProposalServiceTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JOB_UUID = "e53780aa-cad6-4c44-b088-c9a681afe40f";

    private final FakeProposalMapper proposals = new FakeProposalMapper();
    private final FakeServerMapper servers = new FakeServerMapper();
    private final FakeAccountMapper accounts = new FakeAccountMapper();
    private final FakeJobMapper jobs = new FakeJobMapper();
    private final FakeGateway gateway = new FakeGateway();
    private final ProposalService service;

    ProposalServiceTest()
    {
        servers.record = ProposalTestFakes.server(4L, true);
        accounts.record = ProposalTestFakes.account(8L, 4L, 1L, null, true);
        service = new ProposalService(proposals, ProposalTestFakes.registry(jobs), servers,
                accounts, gateway, JSON);
    }

    @Test
    void createRendersPendingManualProposal()
    {
        CommandProposalRecord created = service.create(1L, 4L, 8L, "probe-loadavg", null);
        assertEquals("PENDING", created.status());
        assertEquals("MANUAL", created.source());
        assertEquals("cat /proc/loadavg", created.renderedCommand());
        assertEquals("{}", created.argsJson());
        assertNull(created.exitCode());
        assertNotNull(created.createdAt());
        assertEquals(1, proposals.rows.size());
    }

    @Test
    void createValidatesRequestShapeOwnershipAndAvailability()
    {
        assertInvalidRequest(() -> service.create(1L, null, 8L, "probe-loadavg", null));
        assertInvalidRequest(() -> service.create(1L, 4L, null, "probe-loadavg", null));
        assertInvalidRequest(() -> service.create(1L, 4L, 8L, null, null));
        assertInvalidRequest(() -> service.create(1L, 4L, 8L, "probe-loadavg", JSON.valueToTree(List.of(1))));
        assertEquals("UNKNOWN_PROPOSAL_ACTION",
            assertThrows(ServiceException.class, () -> service.create(1L, 4L, 8L, "rm-rf", null)).getMessage());

        servers.record = null;
        assertUnavailable(() -> service.create(1L, 4L, 8L, "probe-loadavg", null));
        servers.record = ProposalTestFakes.server(4L, false);
        assertUnavailable(() -> service.create(1L, 4L, 8L, "probe-loadavg", null));

        servers.record = ProposalTestFakes.server(4L, true);
        accounts.record = ProposalTestFakes.account(8L, 4L, 2L, null, true);
        assertUnavailable(() -> service.create(1L, 4L, 8L, "probe-loadavg", null));
        accounts.record = ProposalTestFakes.account(8L, 5L, 1L, null, true);
        assertUnavailable(() -> service.create(1L, 4L, 8L, "probe-loadavg", null));
        accounts.record = ProposalTestFakes.account(8L, 4L, 1L, null, false);
        assertUnavailable(() -> service.create(1L, 4L, 8L, "probe-loadavg", null));
        assertTrue(proposals.rows.isEmpty(), "校验失败不得落库");
    }

    @Test
    void createJobProposalRendersTargetedSystemctlCommand()
    {
        jobs.record = ProposalTestFakes.sshJob(72, 1, 4, 8, JOB_UUID);
        CommandProposalRecord created = service.create(1L, 4L, 8L, "job-inspect",
                JSON.valueToTree(Map.of("jobId", 72)));
        assertEquals("systemctl --user show gputrain-" + JOB_UUID + ".service --no-pager"
                + " --property=LoadState,ActiveState,SubState,Result,ExecMainPID,ExecMainStatus,ControlGroup",
            created.renderedCommand());
        assertEquals("{\"jobId\":72}", created.argsJson());
        // 参数非法时提案不落库
        assertInvalidRequest(() -> service.create(1L, 4L, 8L, "job-inspect", null));
        assertEquals(1, proposals.rows.size());
    }

    @Test
    void createAgentProposalRequiresRationaleAndStoresSource()
    {
        CommandProposalRecord created = service.create(1L, 4L, 8L, "probe-loadavg", null,
                "AGENT", "用户想了解系统负载，目录中 probe-loadavg 直接给出 1/5/15 分钟均值");
        assertEquals("AGENT", created.source());
        assertEquals("用户想了解系统负载，目录中 probe-loadavg 直接给出 1/5/15 分钟均值", created.rationale());
        assertEquals("PENDING", created.status());
        assertEquals("AGENT", proposals.rows.get(created.proposalId()).source());
        // AGENT 缺依据 / 空白依据 / 超长依据均拒绝
        assertInvalidRequest(() -> service.create(1L, 4L, 8L, "probe-loadavg", null, "AGENT", null));
        assertInvalidRequest(() -> service.create(1L, 4L, 8L, "probe-loadavg", null, "AGENT", "   "));
        assertInvalidRequest(() -> service.create(1L, 4L, 8L, "probe-loadavg", null, "AGENT", "x".repeat(1025)));
        // 非法来源与 MANUAL 携带依据均拒绝
        assertInvalidRequest(() -> service.create(1L, 4L, 8L, "probe-loadavg", null, "SYSTEM", "r"));
        assertInvalidRequest(() -> service.create(1L, 4L, 8L, "probe-loadavg", null, "MANUAL", "r"));
        assertEquals(1, proposals.rows.size(), "校验失败不得落库");
    }

    @Test
    void approveExecutesAndAttachesResult()
    {
        gateway.session.result = new CommandResult(0, "0.50 0.25 0.10\n", "");
        CommandProposalRecord created = service.create(1L, 4L, 8L, "probe-gpu", null);
        CommandProposalRecord approved = service.approve(created.proposalId(), 1L, false);
        assertEquals("EXECUTED", approved.status());
        assertEquals(0, approved.exitCode());
        assertEquals("0.50 0.25 0.10\n", approved.stdout());
        assertNotNull(approved.decidedAt());
        assertNotNull(approved.executedAt());
        assertEquals(List.of(List.of("nvidia-smi",
                "--query-gpu=gpu_uuid,name,index,memory.total,memory.used,utilization.gpu,mig.mode.current,driver_version",
                "--format=csv,noheader,nounits")), gateway.session.executed);
        assertTrue(proposals.failedCodes.isEmpty());
    }

    @Test
    void approveRendersFromStoredActionAndArgsNotStoredCommandText()
    {
        jobs.record = ProposalTestFakes.sshJob(72, 1, 4, 8, JOB_UUID);
        CommandProposalRecord created = service.create(1L, 4L, 8L, "job-inspect",
                JSON.valueToTree(Map.of("jobId", 72)));
        // 篡改落库的 rendered_command（模拟存储被改写），批准仍按 action + args 二次渲染
        proposals.rows.put(created.proposalId(), new CommandProposalRecord(created.proposalId(),
                created.actorUserId(), created.serverId(), created.accountId(), created.source(),
                created.rationale(), created.action(), created.argsJson(), "cat /etc/shadow", created.status(),
                created.exitCode(), created.stdout(), created.stderr(), created.errorCode(),
                created.createdAt(), created.decidedAt(), created.executedAt()));
        CommandProposalRecord approved = service.approve(created.proposalId(), 1L, false);
        assertEquals("EXECUTED", approved.status());
        assertEquals(0, approved.exitCode());
        assertEquals(List.of("systemctl", "--user", "show", "gputrain-" + JOB_UUID + ".service",
                "--no-pager", "--property=LoadState,ActiveState,SubState,Result,ExecMainPID,ExecMainStatus,ControlGroup"),
            gateway.session.executed.get(0));
    }

    @Test
    void secondApproveIsRejectedByCompareAndSet()
    {
        String proposalId = service.create(1L, 4L, 8L, "probe-loadavg", null).proposalId();
        service.approve(proposalId, 1L, false);
        assertEquals("PROPOSAL_NOT_PENDING",
            assertThrows(ServiceException.class, () -> service.approve(proposalId, 1L, false)).getMessage());
        assertEquals(1, gateway.session.executed.size(), "命令只允许执行一次");
        assertEquals("EXECUTED", proposals.findById(proposalId).status());
    }

    @Test
    void rejectSkipsExecutionAndCasRejectsTwice()
    {
        String proposalId = service.create(1L, 4L, 8L, "probe-loadavg", null).proposalId();
        CommandProposalRecord rejected = service.reject(proposalId, 1L, false);
        assertEquals("REJECTED", rejected.status());
        assertNotNull(rejected.decidedAt());
        assertTrue(gateway.session.executed.isEmpty(), "拒绝不得触发远端命令");
        assertEquals("PROPOSAL_NOT_PENDING",
            assertThrows(ServiceException.class, () -> service.reject(proposalId, 1L, false)).getMessage());
        assertEquals("PROPOSAL_NOT_PENDING",
            assertThrows(ServiceException.class, () -> service.approve(proposalId, 1L, false)).getMessage());
        assertTrue(gateway.session.executed.isEmpty());
    }

    @Test
    void nonZeroExitCodeIsExecutedNotFailed()
    {
        gateway.session.result = new CommandResult(1, "", "System has not been booted with systemd");
        String proposalId = service.create(1L, 4L, 8L, "probe-systemd", null).proposalId();
        CommandProposalRecord approved = service.approve(proposalId, 1L, false);
        assertEquals("EXECUTED", approved.status());
        assertEquals(1, approved.exitCode());
        assertEquals("System has not been booted with systemd", approved.stderr());
        assertTrue(proposals.failedCodes.isEmpty(), "exitCode≠0 是合法可观测结果，不是执行层失败");
    }

    @Test
    void transportFailureMarksProposalFailed()
    {
        gateway.session.failure = new SshException(ErrorCode.AUTH_FAILED);
        String proposalId = service.create(1L, 4L, 8L, "probe-loadavg", null).proposalId();
        CommandProposalRecord failed = service.approve(proposalId, 1L, false);
        assertEquals("FAILED", failed.status());
        assertEquals("AUTH_FAILED", failed.errorCode());
        assertNotNull(failed.executedAt());
    }

    @Test
    void reRenderFailureMarksProposalFailed()
    {
        jobs.record = ProposalTestFakes.sshJob(72, 1, 4, 8, JOB_UUID);
        String proposalId = service.create(1L, 4L, 8L, "job-inspect",
                JSON.valueToTree(Map.of("jobId", 72))).proposalId();
        jobs.record = null; // 批准前任务被删除：二次渲染失败
        CommandProposalRecord failed = service.approve(proposalId, 1L, false);
        assertEquals("FAILED", failed.status());
        assertEquals("INVALID_PROPOSAL_REQUEST", failed.errorCode());
        assertTrue(gateway.session.executed.isEmpty());
    }

    @Test
    void ownershipGuardsApplyAndAdminSeesAll()
    {
        String proposalId = service.create(1L, 4L, 8L, "probe-loadavg", null).proposalId();
        assertEquals("PROPOSAL_NOT_FOUND",
            assertThrows(ServiceException.class, () -> service.approve(proposalId, 2L, false)).getMessage());
        assertEquals("PROPOSAL_NOT_FOUND",
            assertThrows(ServiceException.class, () -> service.reject(proposalId, 2L, false)).getMessage());
        assertEquals(1, service.list(1L, false).size());
        assertTrue(service.list(2L, false).isEmpty());
        assertEquals(1, service.list(2L, true).size());
        // admin 可以代为批准他人提案
        assertEquals("EXECUTED", service.approve(proposalId, 2L, true).status());
    }

    private static void assertInvalidRequest(org.junit.jupiter.api.function.Executable action)
    {
        assertEquals("INVALID_PROPOSAL_REQUEST",
            assertThrows(ServiceException.class, action).getMessage());
    }

    private static void assertUnavailable(org.junit.jupiter.api.function.Executable action)
    {
        assertEquals("SERVER_ACCOUNT_UNAVAILABLE",
            assertThrows(ServiceException.class, action).getMessage());
    }
}
