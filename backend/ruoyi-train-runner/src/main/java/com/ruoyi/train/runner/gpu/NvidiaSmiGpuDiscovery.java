package com.ruoyi.train.runner.gpu;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryReport;
import com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryStatus;
import com.ruoyi.train.runner.protocol.gpu.GpuSnapshot;

/**
 * Discovers NVIDIA GPUs via fixed nvidia-smi argv (no shell). Only an OK result
 * with parsed devices is observable; every other failure is explicitly non-free.
 */
public final class NvidiaSmiGpuDiscovery implements GpuDiscovery
{
    private static final String GPU_QUERY =
            "gpu_uuid,name,index,memory.total,memory.used,utilization.gpu,mig.mode.current,driver_version";
    private static final String COMPUTE_QUERY =
            "gpu_uuid,pid,process_name,used_memory";
    private static final String FORMAT = "csv,noheader,nounits";

    private final String runnerId;
    private final String binary;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final CommandExecutor executor;

    public NvidiaSmiGpuDiscovery(String runnerId, String binary, Duration timeout,
            int maxOutputBytes, CommandExecutor executor)
    {
        this.runnerId = runnerId;
        this.binary = binary;
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.executor = executor;
    }

    public GpuDiscoveryReport discover()
    {
        Instant now = Instant.now();
        String hostName = hostName();
        try
        {
            String gpuCsv = run(List.of(binary, "--query-gpu=" + GPU_QUERY,
                    "--format=" + FORMAT)).stdout();
            String computeCsv = run(List.of(binary, "--query-compute-apps=" + COMPUTE_QUERY,
                    "--format=" + FORMAT)).stdout();
            String versionText = run(List.of(binary, "--version")).stdout();

            Map<String, List<com.ruoyi.train.runner.protocol.gpu.GpuProcessInfo>> compute =
                    NvidiaSmiCsvParser.parseCompute(computeCsv);
            NvidiaSmiCsvParser.GpuQueryResult query =
                    NvidiaSmiCsvParser.parseGpuQuery(gpuCsv, now, compute);
            List<GpuSnapshot> gpus = query.gpus();
            if (gpus.isEmpty())
                return report(now, hostName, null, null, null,
                        GpuDiscoveryStatus.NO_DEVICES, null);
            String smiVersion = parseSmiVersion(versionText);
            String signature = signature(hostName, query.driverVersion(), smiVersion);
            return report(now, hostName, query.driverVersion(), smiVersion, signature,
                    GpuDiscoveryStatus.OK, gpus);
        }
        catch (CommandTimeoutException timeoutFailure)
        {
            return report(now, hostName, null, null, null,
                    GpuDiscoveryStatus.TIMEOUT, null);
        }
        catch (java.io.IOException ioFailure)
        {
            GpuDiscoveryStatus status =
                    String.valueOf(ioFailure.getMessage()).contains("Permission denied")
                            ? GpuDiscoveryStatus.PERMISSION_DENIED
                            : GpuDiscoveryStatus.UNAVAILABLE;
            return report(now, hostName, null, null, null, status, null);
        }
        catch (IllegalArgumentException parseFailure)
        {
            return report(now, hostName, null, null, null,
                    GpuDiscoveryStatus.CORRUPT_OUTPUT, null);
        }
    }

    private ExecResult run(List<String> argv) throws java.io.IOException
    {
        ExecResult result = executor.run(argv, timeout, maxOutputBytes);
        if (result.exitCode() != 0)
            throw new java.io.IOException("nvidia-smi exited with " + result.exitCode());
        return result;
    }

    private static String parseSmiVersion(String versionText)
    {
        for (String line : versionText.split("\n"))
        {
            String stripped = line.strip();
            if (stripped.startsWith("NVIDIA-SMI"))
            {
                String[] parts = stripped.split("\\s+");
                if (parts.length >= 3) return parts[2];
            }
        }
        return null;
    }

    private static String hostName()
    {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) return env;
        try
        {
            return InetAddress.getLocalHost().getHostName();
        }
        catch (Exception failure)
        {
            return "unknown-host";
        }
    }

    private static String signature(String hostName, String driverVersion, String smiVersion)
    {
        String raw = hostName + "|" + driverVersion + "|" + smiVersion;
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException failure)
        {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private GpuDiscoveryReport report(Instant now, String hostName, String driverVersion,
            String smiVersion, String signature, GpuDiscoveryStatus status,
            List<GpuSnapshot> gpus)
    {
        return new GpuDiscoveryReport(runnerId, hostName, driverVersion, smiVersion,
                signature, now, status, gpus == null ? List.of() : gpus, null);
    }
}
