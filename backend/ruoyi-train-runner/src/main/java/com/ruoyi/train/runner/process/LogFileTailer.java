package com.ruoyi.train.runner.process;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import com.ruoyi.train.runner.protocol.RunnerLogChunk;

/** Reads only bounded, complete-line byte ranges from files under the Runner root. */
public final class LogFileTailer
{
    private final Path root;
    public LogFileTailer(Path root) throws IOException { this.root = Objects.requireNonNull(root).toRealPath(); }

    public byte[] read(Path path, long offset, int maxBytes, boolean terminal) throws IOException
    {
        if (offset < 0 || maxBytes < 1 || maxBytes > RunnerLogChunk.MAX_BYTES)
            throw new IllegalArgumentException("Invalid log read bounds");
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Log path is outside the allowed root");
        Path canonical = absolute.toRealPath();
        if (!canonical.startsWith(root)) throw new IOException("Log path is outside the allowed root");
        try (FileChannel channel = FileChannel.open(canonical, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        {
            long size = channel.size();
            if (offset > size) throw new IOException("Log offset exceeds file size");
            int count = (int) Math.min(maxBytes, size - offset);
            if (count == 0) return new byte[0];
            ByteBuffer buffer = ByteBuffer.allocate(count);
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            byte[] bytes = buffer.array();
            if (terminal) return bytes;
            int newline = -1;
            for (int i = bytes.length - 1; i >= 0; i--) if (bytes[i] == '\n') { newline = i; break; }
            if (newline >= 0) return java.util.Arrays.copyOf(bytes, newline + 1);
            if (bytes.length < maxBytes) return new byte[0];
            int lead = bytes.length - 1;
            while (lead >= 0 && (bytes[lead] & 0xc0) == 0x80) lead--;
            if (lead < 0) return new byte[0];
            int first = bytes[lead] & 0xff;
            int expected = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
            int safeLength = bytes.length - lead < expected ? lead : bytes.length;
            return safeLength == 0 ? new byte[0] : java.util.Arrays.copyOf(bytes, safeLength);
        }
    }
}
