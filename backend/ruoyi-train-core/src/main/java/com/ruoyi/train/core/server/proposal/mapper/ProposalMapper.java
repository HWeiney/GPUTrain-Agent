package com.ruoyi.train.core.server.proposal.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.train.core.server.proposal.domain.CommandProposalRecord;

/**
 * 命令提案持久化：批准/拒绝走 CAS 乐观迁移（WHERE status='PENDING'，0 行即已被处理）；
 * attachResult/markFailed 仅在 EXECUTED 且 exit_code 尚未写入时生效（防并发二次写入）。
 */
public interface ProposalMapper
{
    int insert(CommandProposalRecord record);

    CommandProposalRecord findById(@Param("proposalId") String proposalId);

    List<CommandProposalRecord> listForActor(@Param("actorUserId") Long actorUserId);

    List<CommandProposalRecord> listAll();

    int markExecuted(@Param("proposalId") String proposalId, @Param("decidedAt") Instant decidedAt);

    int markRejected(@Param("proposalId") String proposalId, @Param("decidedAt") Instant decidedAt);

    int attachResult(@Param("proposalId") String proposalId, @Param("exitCode") int exitCode,
            @Param("stdout") String stdout, @Param("stderr") String stderr,
            @Param("executedAt") Instant executedAt);

    int markFailed(@Param("proposalId") String proposalId, @Param("errorCode") String errorCode,
            @Param("executedAt") Instant executedAt);
}
