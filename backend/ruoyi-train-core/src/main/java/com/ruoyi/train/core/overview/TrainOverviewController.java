package com.ruoyi.train.core.overview;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;

@RestController
@RequestMapping("/train/overview")
public class TrainOverviewController
{
    private final TrainOverviewService service;

    public TrainOverviewController(TrainOverviewService service)
    {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('train:overview:view')")
    @GetMapping
    public AjaxResult overview()
    {
        return AjaxResult.success(service.getOverview());
    }
}
