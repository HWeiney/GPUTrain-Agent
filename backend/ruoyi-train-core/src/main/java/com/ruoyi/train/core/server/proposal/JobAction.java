package com.ruoyi.train.core.server.proposal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.job.domain.JobRecord;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.server.job.SystemdCommandBuilder;

/**
 * 训练任务控制动作：jobId → 归属与 SSH 模式校验 → systemd 单元 identity →
 * 渲染 systemctl --user show/stop（进入 MinaSshGateway 动态白名单）。
 * job-start 不走提案：必须先 SFTP 上传 run.sh 再 exec，属 SshJobDispatcher 状态机职责。
 */
final class JobAction implements CommandAction
{
    enum Kind { INSPECT, STOP }

    private final Kind kind;
    private final JobService jobs;
    private final SystemdCommandBuilder commands = new SystemdCommandBuilder();

    JobAction(Kind kind, JobService jobs)
    {
        this.kind = Objects.requireNonNull(kind);
        this.jobs = Objects.requireNonNull(jobs);
    }

    @Override public String name()
    {
        return kind == Kind.INSPECT ? "job-inspect" : "job-stop";
    }

    @Override public String description()
    {
        return kind == Kind.INSPECT ? "查看训练任务的 systemd 用户单元状态" : "停止训练任务的 systemd 用户单元";
    }

    @Override public List<ActionParam> params()
    {
        return List.of(new ActionParam("jobId", "long", true, "训练任务 ID"));
    }

    @Override public List<String> render(JsonNode args, CommandContext ctx)
    {
        long jobId = requireJobId(args);
        JobRecord job;
        try { job = jobs.getOwned(jobId, ctx.actorUserId(), false); }
        catch (IllegalArgumentException e) { throw new ServiceException("INVALID_PROPOSAL_REQUEST"); }
        // 任务必须是提案目标服务器/账号上的 SSH 任务，防止跨账号引用单元
        if (!"SSH".equals(job.executionMode())
                || !ctx.server().serverId().equals(job.serverId())
                || !ctx.account().accountId().equals(job.accountId()))
            throw new ServiceException("INVALID_PROPOSAL_REQUEST");
        try
        {
            SystemdCommandBuilder.RemoteJobIdentity identity = commands.identity(job.jobId(),
                    UUID.fromString(job.jobUuid()), job.serverId(), job.accountId(),
                    ctx.account().allowedRoot());
            return kind == Kind.INSPECT ? commands.inspectCommand(identity) : commands.stopCommand(identity);
        }
        catch (IllegalArgumentException e) { throw new ServiceException("INVALID_PROPOSAL_REQUEST"); }
    }

    /** 仅接受恰好一个整数字段 jobId（≥1），拒绝其余任何参数。 */
    private static long requireJobId(JsonNode args)
    {
        if (args == null || !args.isObject() || args.size() != 1)
            throw new ServiceException("INVALID_PROPOSAL_REQUEST");
        JsonNode jobId = args.get("jobId");
        if (jobId == null || !jobId.isIntegralNumber() || jobId.asLong() < 1)
            throw new ServiceException("INVALID_PROPOSAL_REQUEST");
        return jobId.asLong();
    }
}
