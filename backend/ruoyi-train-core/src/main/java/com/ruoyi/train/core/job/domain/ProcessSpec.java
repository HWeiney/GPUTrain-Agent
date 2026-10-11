package com.ruoyi.train.core.job.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ProcessSpec(String workingDirectory, String executable,
        List<String> arguments, Map<String, String> environment,
        long timeoutSeconds, String idempotencyKey)
{
    public ProcessSpec
    {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(executable, "executable");
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
}
