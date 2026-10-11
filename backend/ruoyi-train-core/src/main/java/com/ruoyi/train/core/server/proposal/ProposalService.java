package com.ruoyi.train.core.server.proposal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.mapper.ServerAccountMapper;
import com.ruoyi.train.core.server.mapper.ServerMapper;
import com.ruoyi.train.core.server.proposal.CommandAction.CommandContext;
import com.ruoyi.train.core.server.proposal.domain.CommandProposalRecord;
import com.ruoyi.train.core.server.proposal.mapper.ProposalMapper;
import com.ruoyi.train.core.server.ssh.MinaSshGateway;
import com.ruoyi.train.core.server.ssh.SshGateway;
import com.ruoyi.train.core.server.ssh.SshGateway.CommandResult;
import com.ruoyi.train.core.server.ssh.SshGateway.SshException;

/**
 * 命令提案闭环：创建（渲染 + 白名单预校验 → PENDING）→
 * 批准（CAS 迁移 → 从 action + args_json 二次渲染（不信任落库的 rendered_command 文本）
 * → exec → attachResult / markFailed）/ 拒绝（CAS）→ 列表（本人，admin 全量）。
 */
@Service
public class ProposalService
{
    private static final int MAX_RESULT_BYTES = 64 * 1024;
    private static final int ARGS_JSON_MAX = 2048;
    private static final int RENDERED_COMMAND_MAX = 1024;

    private final ProposalMapper proposals;
    private final CommandActionRegistry registry;
    private final ServerMapper servers;
    private final ServerAccountMapper accounts;
    private final SshGateway gateway;
    private final ObjectMapper json;

    public ProposalService(ProposalMapper proposals, CommandActionRegistry registry,
            ServerMapper servers, ServerAccountMapper accounts, SshGateway gateway, ObjectMapper json)
    {
        this.proposals = Objects.requireNonNull(proposals);
        this.registry = Objects.requireNonNull(registry);
        this.servers = Objects.requireNonNull(servers);
        this.accounts = Objects.requireNonNull(accounts);
        this.gateway = Objects.requireNonNull(gateway);
        this.json = Objects.requireNonNull(json);
    }

    public CommandProposalRecord create(long actorUserId, Long serverId, Long accountId,
            String actionName, JsonNode args)
    { return create(actorUserId, serverId, accountId, actionName, args, "MANUAL", null); }

    public CommandProposalRecord create(long actorUserId, Long serverId, Long accountId,
            String actionName, JsonNode args, String source, String rationale)
    {
        if (serverId == null || accountId == null || actionName == null)
            throw new ServiceException("INVALID_PROPOSAL_REQUEST");
        if (args != null && !args.isObject())
            throw new ServiceException("INVALID_PROPOSAL_REQUEST");
        // 来源与依据约束：AGENT 必须携带依据（展示与审计），MANUAL 不携带
        if (!"MANUAL".equals(source) && !"AGENT".equals(source))
            throw new ServiceException("INVALID_PROPOSAL_REQUEST");
        if ("AGENT".equals(source))
        {
            if (rationale == null || rationale.trim().isEmpty() || rationale.length() > 1024)
                throw new ServiceException("INVALID_PROPOSAL_REQUEST");
        }
        else if (rationale != null)
            throw new ServiceException("INVALID_PROPOSAL_REQUEST");
        // 归属与可用性语义与 TerminalController.ticket 一致
        ServerRecord server = servers.findById(serverId);
        ServerAccountRecord account = accounts.findById(accountId);
        if (server == null || !server.enabled() || account == null
                || !Long.valueOf(actorUserId).equals(account.ownerUserId())
                || !serverId.equals(account.serverId()) || !account.enabled() || !account.hasCredential())
            throw new ServiceException("SERVER_ACCOUNT_UNAVAILABLE");
        CommandAction action = registry.action(actionName);
        String argsJson = writeArgs(args);
        List<String> command = action.render(args, new CommandContext(actorUserId, server, account));
        requireWhitelisted(command);
        String renderedCommand = String.join(" ", command);
        if (argsJson.length() > ARGS_JSON_MAX || renderedCommand.length() > RENDERED_COMMAND_MAX)
            throw new ServiceException("INVALID_PROPOSAL_REQUEST");
        CommandProposalRecord record = new CommandProposalRecord(UUID.randomUUID().toString(),
                actorUserId, serverId, accountId, source, rationale, action.name(), argsJson, renderedCommand,
                "PENDING", null, null, null, null, Instant.now(), null, null);
        if (proposals.insert(record) != 1) throw new IllegalStateException("Unable to insert proposal");
        return record;
    }

    public CommandProposalRecord approve(String proposalId, long actorUserId, boolean isAdmin)
    {
        CommandProposalRecord proposal = requireDecidable(proposalId, actorUserId, isAdmin);
        // CAS：0 行即已被处理（并发批准/二次确认）
        if (proposals.markExecuted(proposalId, Instant.now()) != 1)
            throw new ServiceException("PROPOSAL_NOT_PENDING");
        try
        {
            execute(proposal);
        }
        catch (SshException e)
        {
            proposals.markFailed(proposalId, e.code().name(), Instant.now());
        }
        catch (ServiceException e)
        {
            proposals.markFailed(proposalId, safeErrorCode(e.getMessage()), Instant.now());
        }
        catch (Exception e)
        {
            proposals.markFailed(proposalId, "PROPOSAL_EXEC_ERROR", Instant.now());
        }
        return proposals.findById(proposalId);
    }

    public CommandProposalRecord reject(String proposalId, long actorUserId, boolean isAdmin)
    {
        requireDecidable(proposalId, actorUserId, isAdmin);
        if (proposals.markRejected(proposalId, Instant.now()) != 1)
            throw new ServiceException("PROPOSAL_NOT_PENDING");
        return proposals.findById(proposalId);
    }

    public List<CommandProposalRecord> list(long actorUserId, boolean isAdmin)
    {
        return isAdmin ? proposals.listAll() : proposals.listForActor(actorUserId);
    }

    /** 批准后的执行段：二次渲染 + 复检 + 取带机密视图凭证 + 受限 exec + 结果落库。 */
    private void execute(CommandProposalRecord proposal)
    {
        CommandAction action = registry.action(proposal.action());
        JsonNode args = readArgs(proposal.argsJson());
        ServerRecord server = servers.findById(proposal.serverId());
        ServerAccountRecord metadata = accounts.findById(proposal.accountId());
        if (server == null || !server.enabled() || metadata == null
                || !proposal.serverId().equals(metadata.serverId())
                || !proposal.actorUserId().equals(metadata.ownerUserId()) || !metadata.enabled())
            throw new ServiceException("SERVER_ACCOUNT_UNAVAILABLE");
        // 带机密视图：exec 认证需要解密 ciphertext（findById 是脱敏视图，ciphertext 恒为 NULL）
        ServerAccountRecord credentials = accounts.findCredentialsByAuthorizedAccountId(proposal.accountId());
        if (credentials == null) throw new ServiceException("SERVER_ACCOUNT_UNAVAILABLE");
        List<String> command = action.render(args, new CommandContext(proposal.actorUserId(), server, metadata));
        requireWhitelisted(command);
        CommandResult result = gateway.withVerifiedSession(server, credentials,
                session -> session.exec(command, SshGateway.DEFAULT_COMMAND_TIMEOUT, MAX_RESULT_BYTES));
        proposals.attachResult(proposal.proposalId(), result.exitCode(), result.stdout(),
                result.stderr(), Instant.now());
    }

    /** 归属不符按不存在处理，避免泄露他人提案。 */
    private CommandProposalRecord requireDecidable(String proposalId, long actorUserId, boolean isAdmin)
    {
        if (proposalId == null) throw new ServiceException("PROPOSAL_NOT_FOUND");
        CommandProposalRecord proposal = proposals.findById(proposalId);
        if (proposal == null || (!isAdmin && !proposal.actorUserId().equals(actorUserId)))
            throw new ServiceException("PROPOSAL_NOT_FOUND");
        return proposal;
    }

    private static void requireWhitelisted(List<String> command)
    {
        if (!MinaSshGateway.isAllowedCommand(command))
            throw new IllegalStateException("PROPOSAL_COMMAND_NOT_WHITELISTED");
    }

    private String writeArgs(JsonNode args)
    {
        if (args == null || args.isEmpty()) return "{}";
        try { return json.writeValueAsString(args); }
        catch (JsonProcessingException e) { throw new ServiceException("INVALID_PROPOSAL_REQUEST"); }
    }

    private JsonNode readArgs(String argsJson)
    {
        try { return json.readTree(argsJson); }
        catch (JsonProcessingException e) { throw new ServiceException("INVALID_PROPOSAL_REQUEST"); }
    }

    /** error_code 列宽 32：超宽或空消息回退为通用码，保证 FAILED 语义总是落库。 */
    private static String safeErrorCode(String message)
    {
        return message != null && !message.isEmpty() && message.length() <= 32 ? message : "PROPOSAL_EXEC_ERROR";
    }
}
