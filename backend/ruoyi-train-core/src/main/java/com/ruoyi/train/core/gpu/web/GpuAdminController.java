package com.ruoyi.train.core.gpu.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.train.core.server.service.ServerDeviceQueryService;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.train.core.gpu.mapper.GpuDeviceMapper;

/**
 * Read-only administrative API for GPU devices. The returned data is a view of the
 * latest observed facts; it is never treated as authority for scheduling.
 */
@RestController
@RequestMapping("/train/gpu")
public class GpuAdminController
{
    private final GpuDeviceMapper deviceMapper;

    private final ServerDeviceQueryService serverDevices;

    public GpuAdminController(GpuDeviceMapper deviceMapper, ServerDeviceQueryService serverDevices)
    {
        this.deviceMapper = deviceMapper;
        this.serverDevices = serverDevices;
    }

    @PreAuthorize("@ss.hasPermi('train:gpu:view')")
    @GetMapping("/server-devices")
    public AjaxResult serverDevices(@RequestParam Long serverId, @RequestParam Long accountId)
    {
        return AjaxResult.success(serverDevices.list(serverId, accountId, SecurityUtils.getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('train:gpu:view')")
    @GetMapping("/devices")
    public AjaxResult list()
    {
        return AjaxResult.success(deviceMapper.listDevices());
    }
}
