package com.ruoyi.train.runner.protocol;

import java.time.Instant;
import java.util.Objects;

/**
 * Runner发送给控制面的最小心跳协议。
 *
 * @param runnerId Runner唯一标识
 * @param linuxUsername Runner所属Linux用户
 * @param sentAt Runner生成心跳的时间
 */
public record RunnerHeartbeat(String runnerId, String linuxUsername, Instant sentAt)
{
    public RunnerHeartbeat
    {
        Objects.requireNonNull(runnerId, "runnerId");
        Objects.requireNonNull(linuxUsername, "linuxUsername");
        Objects.requireNonNull(sentAt, "sentAt");
    }
}
