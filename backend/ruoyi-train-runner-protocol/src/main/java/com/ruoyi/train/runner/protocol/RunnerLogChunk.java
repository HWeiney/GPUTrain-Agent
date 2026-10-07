package com.ruoyi.train.runner.protocol;

import java.util.Objects;
import java.util.UUID;

/** A bounded, byte-addressed log segment. Jackson encodes content as base64. */
public record RunnerLogChunk(UUID commandId, RunnerLogStream stream, long offset, byte[] content, boolean terminal)
{
    public static final int MAX_BYTES = 32 * 1024;

    public RunnerLogChunk
    {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(content, "content");
        if (offset < 0 || content.length == 0 || content.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid log chunk bounds");
        content = content.clone();
    }

    @Override public byte[] content() { return content.clone(); }
    public long nextOffset() { return offset + content.length; }
}
