package com.ruoyi.train.runner.process;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ruoyi.train.runner.protocol.RunnerProcessStatus;

/** One atomically replaced JSON document per command under the allowed root. */
public final class ProcessStateStore
{
    private final Path root;
    private final Path directory;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ProcessStateStore(Path allowedRoot) throws IOException
    {
        root = allowedRoot.toRealPath();
        directory = root.resolve(".gputrain/state");
        checkedDirectory();
    }

    public synchronized void save(ManagedProcess process) throws IOException
    {
        Objects.requireNonNull(process);
        checkedDirectory();
        Path temporary = directory.resolve(process.commandId() + ".json.tmp");
        Path target = directory.resolve(process.commandId() + ".json");
        byte[] bytes = json.writeValueAsBytes(process);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
        {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory();
    }

    public synchronized List<ManagedProcess> loadAll() throws IOException
    {
        checkedDirectory();
        List<ManagedProcess> result = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.json"))
        {
            for (Path file : files)
            {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid process state entry");
                ManagedProcess process;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))
                { process = json.readValue(input, ManagedProcess.class); }
                if (!file.getFileName().toString().equals(process.commandId() + ".json"))
                    throw new IOException("Process state filename does not match command ID");
                result.add(process);
            }
        }
        result.sort(Comparator.comparing(ManagedProcess::commandId));
        return result;
    }

    public synchronized ManagedProcess markTerminal(UUID commandId, RunnerProcessStatus status, Integer exitCode) throws IOException
    {
        if (status != RunnerProcessStatus.SUCCEEDED && status != RunnerProcessStatus.FAILED
                && status != RunnerProcessStatus.STOPPED)
            throw new IllegalArgumentException("Terminal process status required");
        ManagedProcess process = loadAll().stream().filter(p -> p.commandId().equals(commandId))
                .findFirst().orElseThrow(() -> new IOException("Process state not found"));
        if (process.status() == RunnerProcessStatus.SUCCEEDED || process.status() == RunnerProcessStatus.FAILED
                || process.status() == RunnerProcessStatus.STOPPED) return process;
        ManagedProcess terminal = process.withStatus(status, exitCode);
        save(terminal);
        return terminal;
    }

    private void checkedDirectory() throws IOException
    {
        Path parent = root.resolve(".gputrain");
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(parent);
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid process state directory");
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !directory.toRealPath().startsWith(root))
            throw new IOException("Invalid process state directory");
    }

    private void forceDirectory() throws IOException
    {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ))
        { channel.force(true); }
        catch (UnsupportedOperationException | AccessDeniedException unsupported)
        { /* Some filesystems cannot fsync directories; the file and rename were already forced. */ }
    }
}
