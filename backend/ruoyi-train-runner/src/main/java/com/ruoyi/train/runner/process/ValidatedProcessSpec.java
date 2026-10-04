package com.ruoyi.train.runner.process;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record ValidatedProcessSpec(Path workingDirectory, Path executable, List<String> arguments,
        Map<String,String> environment, long timeoutSeconds, String idempotencyKey, Path stdoutPath, Path stderrPath)
{
    public ValidatedProcessSpec
    {
        arguments = List.copyOf(arguments);
        environment = Map.copyOf(environment);
    }
}
