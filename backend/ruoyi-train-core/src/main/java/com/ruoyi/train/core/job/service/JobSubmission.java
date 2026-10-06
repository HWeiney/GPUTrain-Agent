package com.ruoyi.train.core.job.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ruoyi.train.runner.protocol.ProcessSpec;

/**
 * Immutable request to create a training job. The request {@link #requestKey()} makes
 * repeated submissions idempotent; the process specification is validated and persisted
 * by the control plane and never built from a shell string.
 *
 * @param ownerUserId authenticated user that owns the job
 * @param gpuCount number of GPUs required (must all be on one host)
 * @param minMemoryMiB minimum total memory per GPU in MiB
 * @param allowedUuids optional whitelist of GPU UUIDs
 * @param deniedUuids optional blacklist of GPU UUIDs
 * @param requestKey client-supplied idempotency key for the request
 * @param workingDirectory allowed working directory for the fixture
 * @param executable executable to run
 * @param arguments fixed argument vector (no shell interpolation)
 * @param environment allowed environment for the process
 * @param timeoutSeconds hard timeout for the process
 */
public record JobSubmission(long ownerUserId, int gpuCount, long minMemoryMiB,
        Set<String> allowedUuids, Set<String> deniedUuids, String requestKey,
        String workingDirectory, String executable, List<String> arguments,
        Map<String, String> environment, long timeoutSeconds,
        long minFreeMemoryMiB, boolean allowExternalOccupancy)
{
    public JobSubmission(long ownerUserId, int gpuCount, long minMemoryMiB,
            Set<String> allowedUuids, Set<String> deniedUuids, String requestKey,
            String workingDirectory, String executable, List<String> arguments,
            Map<String, String> environment, long timeoutSeconds)
    { this(ownerUserId, gpuCount, minMemoryMiB, allowedUuids, deniedUuids, requestKey,
            workingDirectory, executable, arguments, environment, timeoutSeconds, 0, false); }
    /**
     * Builds the structured process specification for this job. The command idempotency
     * key is derived deterministically from the request key so retries never produce a
     * second process.
     */
    public ProcessSpec toProcessSpec()
    {
        return new ProcessSpec(workingDirectory, executable, arguments, environment,
                timeoutSeconds, "job-start:" + requestKey);
    }
}
