package com.ruoyi.train.core.server.proposal;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.server.proposal.CommandAction.CommandContext;
import com.ruoyi.train.core.server.ssh.MinaSshGateway;
import com.ruoyi.train.core.server.proposal.ProposalTestFakes.FakeJobMapper;

class CommandActionRegistryTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JOB_UUID = "e53780aa-cad6-4c44-b088-c9a681afe40f";
    private static final String UNIT = "gputrain-e53780aa-cad6-4c44-b088-c9a681afe40f.service";

    private final FakeJobMapper jobs = new FakeJobMapper();
    private final CommandActionRegistry registry = ProposalTestFakes.registry(jobs);

    @Test
    void catalogExposesNineUniqueActionsAndAllProbesWhitelisted()
    {
        List<CommandAction> catalog = registry.catalog();
        assertEquals(9, catalog.size());
        assertEquals(catalog.size(), catalog.stream().map(CommandAction::name).distinct().count());
        for (CommandAction action : catalog)
        {
            if (!action.name().startsWith("probe-")) continue;
            assertTrue(action.params().isEmpty(), action.name());
            assertTrue(action.description() != null && !action.description().isEmpty(), action.name());
            assertTrue(MinaSshGateway.isAllowedCommand(action.render(null, context())),
                action.name() + " 必须渲染白名单命令");
        }
    }

    @Test
    void probesRejectAnyArguments()
    {
        JsonNode args = JSON.valueToTree(Map.of("extra", 1));
        for (CommandAction action : registry.catalog())
        {
            if (!action.name().startsWith("probe-")) continue;
            assertInvalid(() -> action.render(args, context()));
        }
    }

    @Test
    void unknownActionNameIsRejected()
    {
        assertThrows(ServiceException.class, () -> registry.action("rm-rf"));
        assertThrows(ServiceException.class, () -> registry.action(null));
    }

    @Test
    void duplicateActionNamesFailFast()
    {
        CommandAction probe = new CommandActionConfig().probeLoadavg();
        assertThrows(IllegalStateException.class,
            () -> new CommandActionRegistry(List.of(probe, new CommandActionConfig().probeLoadavg())));
    }

    @Test
    void jobActionsRenderWhitelistedSystemdCommandsForOwnedSshJob()
    {
        jobs.record = ProposalTestFakes.sshJob(72, 1, 4, 8, JOB_UUID);
        JsonNode args = JSON.valueToTree(Map.of("jobId", 72));
        List<String> inspect = registry.action("job-inspect").render(args, context());
        assertEquals(List.of("systemctl", "--user", "show", UNIT, "--no-pager",
                "--property=LoadState,ActiveState,SubState,Result,ExecMainPID,ExecMainStatus,ControlGroup"),
            inspect);
        assertTrue(MinaSshGateway.isAllowedCommand(inspect));
        List<String> stop = registry.action("job-stop").render(args, context());
        assertEquals(List.of("systemctl", "--user", "stop", UNIT), stop);
        assertTrue(MinaSshGateway.isAllowedCommand(stop));
        assertEquals(List.of(new CommandAction.ActionParam("jobId", "long", true, "训练任务 ID")),
            registry.action("job-inspect").params());
    }

    @Test
    void jobActionsRejectMissingForeignLegacyOrMismatchedJobs()
    {
        JsonNode args = JSON.valueToTree(Map.of("jobId", 72));
        CommandAction inspect = registry.action("job-inspect");

        // 未找到（fake 返回 null）
        jobs.record = null;
        assertInvalid(() -> inspect.render(args, context()));

        // 归属他人
        jobs.record = ProposalTestFakes.sshJob(72, 2, 4, 8, JOB_UUID);
        assertInvalid(() -> inspect.render(args, context()));

        // 非 SSH 模式（历史任务只读）
        jobs.record = new JobRecord(72L, JOB_UUID, 1L, null, 3, 1000, "[]", "[]", "{}",
            "RUNNING", "req-72", null, null, Instant.now(), Instant.now());
        assertInvalid(() -> inspect.render(args, context()));

        // 目标服务器不符
        jobs.record = ProposalTestFakes.sshJob(72, 1, 5, 8, JOB_UUID);
        assertInvalid(() -> inspect.render(args, context()));

        // 目标账号不符
        jobs.record = ProposalTestFakes.sshJob(72, 1, 4, 9, JOB_UUID);
        assertInvalid(() -> inspect.render(args, context()));
    }

    @Test
    void jobActionsRejectInvalidJobIdArguments()
    {
        jobs.record = ProposalTestFakes.sshJob(72, 1, 4, 8, JOB_UUID);
        CommandAction inspect = registry.action("job-inspect");
        assertInvalid(() -> inspect.render(null, context()));
        assertInvalid(() -> inspect.render(JSON.valueToTree(Map.of()), context()));
        assertInvalid(() -> inspect.render(JSON.valueToTree(Map.of("jobId", "72")), context()));
        assertInvalid(() -> inspect.render(JSON.valueToTree(Map.of("jobId", 0)), context()));
        assertInvalid(() -> inspect.render(JSON.valueToTree(Map.of("jobId", 72, "extra", 1)), context()));
        assertInvalid(() -> inspect.render(JSON.createArrayNode(), context()));
        assertEquals(UUID.fromString(JOB_UUID).toString(), JOB_UUID);
    }

    private CommandContext context()
    {
        return new CommandContext(1L, ProposalTestFakes.server(4L, true),
                ProposalTestFakes.account(8L, 4L, 1L, null, true));
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable render)
    {
        assertEquals("INVALID_PROPOSAL_REQUEST",
            assertThrows(ServiceException.class, render).getMessage());
    }
}
