package com.ruoyi.train.core.server.terminal;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.mapper.ServerAccountMapper;
import com.ruoyi.train.core.server.mapper.ServerMapper;
import com.ruoyi.train.core.server.service.ServerInventoryService;

/**
 * Web 终端 REST 端点：签发一次性握手票据、查询可连接的服务器与账号。
 *
 * WebSocket 握手无法携带 Authorization 头，因此权限判定全部收敛在票据签发端点
 * （train:terminal:connect），握手时拦截器只消费票据。
 */
@RestController
@RequestMapping("/train/terminal")
public class TerminalController
{
    private final ServerInventoryService inventory;
    private final TerminalTicketService tickets;
    private final ServerMapper servers;
    private final ServerAccountMapper accounts;

    public TerminalController(ServerInventoryService inventory, TerminalTicketService tickets,
        ServerMapper servers, ServerAccountMapper accounts)
    {
        this.inventory = inventory;
        this.tickets = tickets;
        this.servers = servers;
        this.accounts = accounts;
    }

    public record TicketRequest(Long serverId, Long accountId) { }

    public record TicketView(String ticket, long expiresInMs) { }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:terminal:connect')")
    @PostMapping("/ticket")
    public AjaxResult ticket(@RequestBody TicketRequest request)
    {
        if (request == null || request.serverId() == null || request.accountId() == null)
            throw new ServiceException("INVALID_TERMINAL_REQUEST");
        Long userId = SecurityUtils.getUserId();
        ServerRecord server = servers.findById(request.serverId());
        ServerAccountRecord account = accounts.findById(request.accountId());
        // 与 ServerInventoryService.requireOwned 相同的归属语义，另加可用性校验
        if (server == null || !server.enabled() || account == null || !userId.equals(account.ownerUserId())
            || !request.serverId().equals(account.serverId()) || !account.enabled() || !account.hasCredential())
            throw new ServiceException("SERVER_ACCOUNT_UNAVAILABLE");
        return AjaxResult.success(new TicketView(tickets.issueTicket(userId, request.serverId(), request.accountId()),
            tickets.ticketTtlMillis()));
    }

    /** 当前用户可连接的服务器与账号列表（复用服务器列表视图结构）。 */
    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:terminal:connect')")
    @GetMapping("/connections")
    public AjaxResult connections()
    {
        return AjaxResult.success(inventory.list(SecurityUtils.getUserId()));
    }
}
