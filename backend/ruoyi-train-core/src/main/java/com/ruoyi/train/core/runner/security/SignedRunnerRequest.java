package com.ruoyi.train.core.runner.security;

import java.util.Objects;

public record SignedRunnerRequest(String runnerId, long timestamp, String nonce, String signature,
        String method, String path, byte[] body)
{
    public SignedRunnerRequest
    {
        body = Objects.requireNonNull(body, "body").clone();
    }

    @Override
    public byte[] body()
    {
        return body.clone();
    }

    @Override
    public String toString()
    {
        return "SignedRunnerRequest[runnerId=" + runnerId + ", method=" + method + ", path=" + path + "]";
    }
}
