package com.ruoyi.train.core.server.proposal;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.server.proposal.CommandAction.ActionParam;
import com.ruoyi.train.core.server.proposal.domain.CommandProposalRecord;

/**
 * 命令提案 REST 端点。动作目录（/actions）是前端动态表单元数据的单一事实源；
 * 创建/批准/拒绝需要 train:proposal:execute（批准即人工确认动作），查询仅需 view。
 */
@RestController
@RequestMapping("/train/proposals")
public class ProposalController
{
    private final ProposalService proposals;
    private final CommandActionRegistry registry;

    public ProposalController(ProposalService proposals, CommandActionRegistry registry)
    {
        this.proposals = proposals;
        this.registry = registry;
    }

    public record CreateProposalRequest(Long serverId, Long accountId, String action, JsonNode args) { }

    public record ActionView(String name, String description, List<ActionParam> params) { }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:proposal:execute')")
    @PostMapping
    public AjaxResult create(@RequestBody CreateProposalRequest request)
    {
        if (request == null) throw new ServiceException("INVALID_PROPOSAL_REQUEST");
        return AjaxResult.success(proposals.create(SecurityUtils.getUserId(), request.serverId(),
                request.accountId(), request.action(), request.args()));
    }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:proposal:view')")
    @GetMapping
    public AjaxResult list()
    {
        Long userId = SecurityUtils.getUserId();
        return AjaxResult.success(proposals.list(userId, SecurityUtils.isAdmin(userId)));
    }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:proposal:view')")
    @GetMapping("/actions")
    public AjaxResult actions()
    {
        return AjaxResult.success(registry.catalog().stream()
                .map(action -> new ActionView(action.name(), action.description(), action.params())).toList());
    }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:proposal:execute')")
    @PostMapping("/{proposalId}/approve")
    public AjaxResult approve(@PathVariable String proposalId)
    {
        Long userId = SecurityUtils.getUserId();
        return AjaxResult.success(proposals.approve(proposalId, userId, SecurityUtils.isAdmin(userId)));
    }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:proposal:execute')")
    @PostMapping("/{proposalId}/reject")
    public AjaxResult reject(@PathVariable String proposalId)
    {
        Long userId = SecurityUtils.getUserId();
        return AjaxResult.success(proposals.reject(proposalId, userId, SecurityUtils.isAdmin(userId)));
    }
}
