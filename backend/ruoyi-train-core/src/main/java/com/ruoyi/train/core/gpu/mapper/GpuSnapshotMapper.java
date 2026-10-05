package com.ruoyi.train.core.gpu.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.gpu.domain.GpuSnapshotRecord;

public interface GpuSnapshotMapper
{
    int insert(GpuSnapshotRecord snapshot);

    int insertBatch(@Param("snapshots") List<GpuSnapshotRecord> snapshots);
}
