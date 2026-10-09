package com.ruoyi.train.core.server.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.server.service.ServerInventoryService;
import com.ruoyi.train.core.server.service.ServerInventoryService.*;

@RestController
@RequestMapping("/train/servers")
public class ServerAdminController {
    private final ServerInventoryService service;
    public ServerAdminController(ServerInventoryService service) { this.service=service; }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:server:view')")
    @GetMapping
    public AjaxResult list() { return AjaxResult.success(service.list(SecurityUtils.getUserId())); }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:server:view')")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Long id,@RequestParam(required=false) Long accountId) {
        return AjaxResult.success(service.detail(id,accountId,SecurityUtils.getUserId()));
    }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:server:manage')")
    @PostMapping("/host-key-preview")
    public AjaxResult preview(@RequestBody PreviewRequest request) {
        return AjaxResult.success(service.preview(request.host(),request.port(),SecurityUtils.getUserId()));
    }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:server:manage')")
    @PostMapping
    public AjaxResult create(@RequestBody CreateServer request) {
        return AjaxResult.success(service.create(request,SecurityUtils.getUserId()));
    }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:server:manage')")
    @PostMapping("/{id}/accounts")
    public AjaxResult assign(@PathVariable Long id,@RequestBody CreateAccount request) {
        return AjaxResult.success(service.assign(id,request,SecurityUtils.getUserId()));
    }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:server:view')")
    @PostMapping("/{id}/probe")
    public AjaxResult probe(@PathVariable Long id,@RequestBody ProbeRequest request) {
        return AjaxResult.success(service.probe(id,request.accountId(),SecurityUtils.getUserId()));
    }

    @PreAuthorize("isAuthenticated() and (@ss.hasPermi('train:server:view') or @ss.hasPermi('train:server:manage'))")
    @PostMapping("/{id}/connection-test")
    public AjaxResult testConnection(@PathVariable Long id,@RequestBody ConnectionTest request) {
        return AjaxResult.success(service.testConnection(id,request,SecurityUtils.getUserId()));
    }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:server:view')")
    @GetMapping("/{id}/devices")
    public AjaxResult devices(@PathVariable Long id,@RequestParam Long accountId) {
        return AjaxResult.success(service.devices(id,accountId,SecurityUtils.getUserId()));
    }

    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:server:manage')")
    @DeleteMapping("/{id}")
    public AjaxResult disable(@PathVariable Long id) {
        service.disable(id,SecurityUtils.getUserId()); return AjaxResult.success();
    }
    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:server:manage')")
    @PutMapping("/{id}")
    public AjaxResult update(@PathVariable Long id,@RequestBody UpdateServer request) {
        return AjaxResult.success(service.update(id,request,SecurityUtils.getUserId()));
    }

    // Owners may rotate their own credentials with view permission; this never grants metadata or assignment rights.
    @PreAuthorize("isAuthenticated() and @ss.hasPermi('train:server:view')")
    @PutMapping("/{id}/accounts/{accountId}")
    public AjaxResult rotate(@PathVariable Long id,@PathVariable Long accountId,@RequestBody RotateCredentials request) {
        return AjaxResult.success(service.rotate(id,accountId,request,SecurityUtils.getUserId()));
    }

    public record PreviewRequest(String host,int port) { }
    public record ProbeRequest(Long accountId) { }
}
