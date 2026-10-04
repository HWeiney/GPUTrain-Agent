package com.ruoyi.train.runner.process;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
import java.util.Set;

/** Opens each component relative to a directory descriptor; no log pathname is reopened. */
final class ProcessLogs implements AutoCloseable
{
    final OutputStream stdout;
    final OutputStream stderr;
    private boolean handedToPumps;

    ProcessLogs(Path stdoutPath, Path stderrPath) throws IOException
    {
        stdout = open(stdoutPath);
        try { stderr = open(stderrPath); }
        catch (IOException | RuntimeException failure)
        {
            try { stdout.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    void handToPumps() { handedToPumps = true; }

    private static OutputStream open(Path path) throws IOException
    {
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IOException("Log path must be canonical and absolute");
        try (DirectoryStream<Path> root = Files.newDirectoryStream(path.getRoot()))
        {
            if (root instanceof SecureDirectoryStream<Path> directory)
                return open(directory, path.getParent().iterator(), path.getFileName());
        }
        // macOS' default provider does not expose SecureDirectoryStream. Keep the
        // descriptor-safe property there: validate the parent immediately before
        // CREATE_NEW/NOFOLLOW, then write only through the returned open channel.
        // Production Linux uses the secure directory-relative branch above.
        Path parent = path.getParent();
        if (!parent.equals(parent.toRealPath()))
            throw new IOException("Log directory changed");
        return Channels.newOutputStream(Files.newByteChannel(path,
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))));
    }

    private static OutputStream open(SecureDirectoryStream<Path> directory, Iterator<Path> parents, Path filename) throws IOException
    {
        if (parents.hasNext())
        {
            try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(parents.next(), LinkOption.NOFOLLOW_LINKS))
            { return open(child, parents, filename); }
        }
        return Channels.newOutputStream(directory.newByteChannel(filename,
            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))));
    }

    @Override public void close() throws IOException
    {
        if (!handedToPumps)
        {
            try { stdout.close(); }
            finally { stderr.close(); }
        }
    }
}
