package com.ruoyi.train.core.job.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.job.domain.JobRecord;

/**
 * Persistence boundary for training jobs. Status updates are conditional so concurrent
 * events cannot silently overwrite each other.
 */
public interface JobMapper
{
    int insert(JobRecord job);

    JobRecord findById(@Param("jobId") long jobId);

    JobRecord findByRequestKey(@Param("requestKey") String requestKey);

    JobRecord findByCommandUuid(@Param("commandUuid") String commandUuid);

    List<JobRecord> listDueSshJobs(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    List<JobRecord> listSshToStart(@Param("limit") int limit);

    List<JobRecord> listSshForReconciliation(@Param("limit") int limit);

    /** Lists jobs for the data scope; admins see all, others only their own jobs. */
    List<JobRecord> listByOwner(@Param("ownerUserId") Long ownerUserId,
            @Param("isAdmin") boolean isAdmin, @Param("limit") int limit,
            @Param("beforeJobId") Long beforeJobId);

    int countActive(@Param("ownerUserId") Long ownerUserId, @Param("isAdmin") boolean isAdmin);

    /**
     * Change the job status only when the current status matches {@code expectedStatus},
     * implementing a compare-and-set transition.
     */
    int compareAndSetStatus(@Param("jobId") long jobId,
            @Param("expectedStatus") String expectedStatus,
            @Param("newStatus") String newStatus,
            @Param("updatedAt") Instant updatedAt);

    /**
     * Attach the scheduled runner and START command and move to STARTING.
     */
    int attachStartCommand(@Param("jobId") long jobId,
            @Param("runnerId") long runnerId,
            @Param("startCommandId") long startCommandId,
            @Param("processSpecJson") String processSpecJson,
            @Param("updatedAt") Instant updatedAt);

    /**
     * Attach the STOP command and move to STOPPING.
     */
    int attachStopCommand(@Param("jobId") long jobId,
            @Param("stopCommandId") long stopCommandId,
            @Param("updatedAt") Instant updatedAt);
}
