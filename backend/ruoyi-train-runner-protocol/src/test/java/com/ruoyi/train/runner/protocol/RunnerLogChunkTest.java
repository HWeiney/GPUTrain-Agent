package com.ruoyi.train.runner.protocol;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunnerLogChunkTest
{
    @Test void nextOffsetUsesRawBytesAndDefensivelyCopiesContent()
    {
        byte[] bytes = { (byte) 0xe4, (byte) 0xb8, (byte) 0xad };
        RunnerLogChunk chunk = new RunnerLogChunk(UUID.randomUUID(), RunnerLogStream.STDOUT, 4, bytes, false);
        bytes[0] = 0;
        assertEquals(7, chunk.nextOffset());
        assertEquals((byte) 0xe4, chunk.content()[0]);
    }

    @Test void rejectsEmptyOversizedAndNegativeOffset()
    {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new RunnerLogChunk(id, RunnerLogStream.STDOUT, 0, new byte[0], false));
        assertThrows(IllegalArgumentException.class, () -> new RunnerLogChunk(id, RunnerLogStream.STDOUT, 0, new byte[RunnerLogChunk.MAX_BYTES + 1], false));
        assertThrows(IllegalArgumentException.class, () -> new RunnerLogChunk(id, RunnerLogStream.STDERR, -1, new byte[]{1}, false));
    }
}
