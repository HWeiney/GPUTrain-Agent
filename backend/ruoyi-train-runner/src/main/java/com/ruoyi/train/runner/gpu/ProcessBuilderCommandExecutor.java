package com.ruoyi.train.runner.gpu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Executes commands directly via ProcessBuilder with bounded output and a timeout. */
public final class ProcessBuilderCommandExecutor implements CommandExecutor
{
    @Override
    public ExecResult run(List<String> argv, Duration timeout, int maxOutputBytes)
            throws IOException
    {
        Process process;
        try
        {
            process = new ProcessBuilder(argv).start();
        }
        catch (IOException failure)
        {
            throw new IOException("Unable to start command: " + argv.get(0), failure);
        }

        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                () -> readBounded(process.getInputStream(), maxOutputBytes));
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                () -> readBounded(process.getErrorStream(), maxOutputBytes));

        boolean finished;
        try
        {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException interrupted)
        {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CommandTimeoutException("Command interrupted");
        }
        if (!finished)
        {
            process.destroyForcibly();
            throw new CommandTimeoutException("Command timed out after " + timeout.toMillis() + "ms");
        }
        return new ExecResult(process.exitValue(), stdout.join(), stderr.join());
    }

    private static String readBounded(InputStream stream, int maxBytes)
    {
        byte[] buffer = new byte[maxBytes + 1];
        int total = 0;
        try (stream)
        {
            while (total < buffer.length)
            {
                int read = stream.read(buffer, total, buffer.length - total);
                if (read < 0) break;
                total += read;
            }
        }
        catch (IOException failure)
        {
            // Treat a broken stream as no output; the exit code still classifies the result.
            return "";
        }
        int length = Math.min(total, maxBytes);
        return new String(buffer, 0, length, StandardCharsets.UTF_8);
    }
}
