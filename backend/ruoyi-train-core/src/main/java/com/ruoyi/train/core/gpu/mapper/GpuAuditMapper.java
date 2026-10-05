package com.ruoyi.train.core.gpu.mapper;

import java.util.List;
import com.ruoyi.train.core.gpu.domain.GpuAuditRecord;

public interface GpuAuditMapper
{
    int insert(GpuAuditRecord audit);

    int insertBatch(List<GpuAuditRecord> records);
}
