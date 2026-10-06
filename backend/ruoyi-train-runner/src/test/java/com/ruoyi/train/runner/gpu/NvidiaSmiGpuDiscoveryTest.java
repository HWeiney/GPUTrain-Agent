package com.ruoyi.train.runner.gpu;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryReport;
import com.ruoyi.train.runner.protocol.gpu.GpuDiscoveryStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NvidiaSmiGpuDiscoveryTest
{
    private static final String GPU_CSV =
            "GPU-aaaaaaaa-0000-0000-0000-000000000000, NVIDIA A100, 0, 40960, 0, 0, Disabled, 550.54.15\n";
    private static final String VERSION =
            "NVIDIA-SMI version 550.54.15\nNVIDIA driver version 550.54.15\n";

    private NvidiaSmiGpuDiscovery discovery(CommandExecutor executor)
    {
        return new NvidiaSmiGpuDiscovery("1", "nvidia-smi", Duration.ofSeconds(10),
                1024 * 1024, executor);
    }

    @Test
    void okWhenBothQueriesSucceed()
    {
        FakeExecutor executor = new FakeExecutor(
                Map.of("query-gpu", new ExecResult(0, GPU_CSV, ""),
                       "query-compute-apps", new ExecResult(0, "", ""),
                       "--version", new ExecResult(0, VERSION, "")));
        GpuDiscoveryReport report = discovery(executor).discover();
        assertEquals(GpuDiscoveryStatus.OK, report.status());
        assertEquals(1, report.gpus().size());
        assertEquals("550.54.15", report.nvidiaSmiVersion());
        assertEquals("550.54.15", report.driverVersion());
        assertEquals("1", report.runnerId());
    }

    @Test
    void unavailableWhenBinaryMissing()
    {
        FakeExecutor executor = new FakeExecutor();
        executor.failWith(new java.io.IOException("no such file"));
        GpuDiscoveryReport report = discovery(executor).discover();
        assertEquals(GpuDiscoveryStatus.UNAVAILABLE, report.status());
        assertEquals(0, report.gpus().size());
    }

    @Test
    void permissionDeniedOnAccessError()
    {
        FakeExecutor executor = new FakeExecutor();
        executor.failWith(new java.io.IOException("Permission denied"));
        GpuDiscoveryReport report = discovery(executor).discover();
        assertEquals(GpuDiscoveryStatus.PERMISSION_DENIED, report.status());
    }

    @Test
    void timeoutWhenExecutorTimesOut()
    {
        FakeExecutor executor = new FakeExecutor();
        executor.failWith(new CommandTimeoutException("timed out"));
        GpuDiscoveryReport report = discovery(executor).discover();
        assertEquals(GpuDiscoveryStatus.TIMEOUT, report.status());
    }

    @Test
    void corruptWhenGpuOutputUnparseable()
    {
        FakeExecutor executor = new FakeExecutor(
                Map.of("query-gpu", new ExecResult(0, "garbage line without enough fields\n", ""),
                       "query-compute-apps", new ExecResult(0, "", ""),
                       "--version", new ExecResult(0, VERSION, "")));
        GpuDiscoveryReport report = discovery(executor).discover();
        assertEquals(GpuDiscoveryStatus.CORRUPT_OUTPUT, report.status());
    }

    @Test
    void noDevicesWhenGpuQueryEmpty()
    {
        FakeExecutor executor = new FakeExecutor(
                Map.of("query-gpu", new ExecResult(0, "", ""),
                       "query-compute-apps", new ExecResult(0, "", ""),
                       "--version", new ExecResult(0, VERSION, "")));
        GpuDiscoveryReport report = discovery(executor).discover();
        assertEquals(GpuDiscoveryStatus.NO_DEVICES, report.status());
    }

    @Test
    void nonZeroExitIsUnavailable()
    {
        FakeExecutor executor = new FakeExecutor(
                Map.of("query-gpu", new ExecResult(9, "", "fail"),
                       "query-compute-apps", new ExecResult(0, "", ""),
                       "--version", new ExecResult(0, VERSION, "")));
        GpuDiscoveryReport report = discovery(executor).discover();
        assertEquals(GpuDiscoveryStatus.UNAVAILABLE, report.status());
    }

    @Test
    void neverUsesShell()
    {
        // All argv entries are discrete elements; the binary and fixed flags are separated.
        FakeExecutor executor = new FakeExecutor(
                Map.of("query-gpu", new ExecResult(0, GPU_CSV, ""),
                       "query-compute-apps", new ExecResult(0, "", ""),
                       "--version", new ExecResult(0, VERSION, "")));
        discovery(executor).discover();
        for (List<String> argv : executor.invocations)
        {
            for (String arg : argv)
            {
                assertEquals(arg, arg.replaceAll("[;&|`$]", ""));
            }
        }
    }

    @Test
    void usesComputeFieldsSupportedByNvidiaSmi570()
    {
        FakeExecutor executor = new FakeExecutor(
                Map.of("query-gpu", new ExecResult(0, GPU_CSV, ""),
                       "query-compute-apps", new ExecResult(0, "", ""),
                       "--version", new ExecResult(0, VERSION, "")));
        discovery(executor).discover();
        assertEquals(true, executor.invocations.stream().flatMap(List::stream)
                .anyMatch(arg -> arg.equals(
                        "--query-compute-apps=gpu_uuid,pid,process_name,used_memory")));
    }

    /** Command executor backed by canned responses keyed by a flag in argv. */
    private static final class FakeExecutor implements CommandExecutor
    {
        private final Map<String, ExecResult> results;
        private final java.util.List<List<String>> invocations = new java.util.ArrayList<>();
        private Throwable failure;

        FakeExecutor() { this(Map.of()); }

        FakeExecutor(Map<String, ExecResult> results) { this.results = results; }

        void failWith(Throwable failure) { this.failure = failure; }

        @Override
        public ExecResult run(List<String> argv, Duration timeout, int maxOutputBytes)
                throws java.io.IOException
        {
            invocations.add(List.copyOf(argv));
            if (failure != null)
            {
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof java.io.IOException io) throw io;
                throw new RuntimeException(failure);
            }
            for (String k : results.keySet())
            {
                if (argv.stream().anyMatch(s -> s.contains(k)))
                    return results.get(k);
            }
            return new ExecResult(0, "", "");
        }
    }
}
