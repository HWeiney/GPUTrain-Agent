package com.ruoyi.train.core.runner.security;

import java.time.Duration;

public interface RunnerNonceStore
{
    boolean claim(String runnerId, String nonce, Duration ttl);
}
