package com.ruoyi.train.runner.process;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileTailerTest
{
    @TempDir Path root;

    @Test void onlyReturnsCompleteUtf8LinesUntilTerminal()
            throws Exception
    {
        Path log = root.resolve("stdout.log");
        Files.writeString(log, "开始\npartial", StandardCharsets.UTF_8);
        LogFileTailer tailer = new LogFileTailer(root);
        byte[] first = tailer.read(log, 0, 100, false);
        assertEquals("开始\n", new String(first, StandardCharsets.UTF_8));
        byte[] finalBytes = tailer.read(log, first.length, 100, true);
        assertEquals("partial", new String(finalBytes, StandardCharsets.UTF_8));
    }

    @Test void rejectsOutsideFilesAndStaleOffsets() throws Exception
    {
        Path log = root.resolve("stdout.log"); Files.writeString(log, "line\n");
        LogFileTailer tailer = new LogFileTailer(root);
        assertThrows(java.io.IOException.class, () -> tailer.read(root.getParent().resolve("other"), 0, 100, true));
        assertThrows(java.io.IOException.class, () -> tailer.read(log, 99, 100, true));
    }

    @Test void splitsLongUtf8LineWithoutBreakingCharacterBoundaries() throws Exception
    {
        Path log = root.resolve("large.log");
        Files.writeString(log, "a".repeat(32 * 1024 - 1) + "中\n", StandardCharsets.UTF_8);
        LogFileTailer tailer = new LogFileTailer(root);
        byte[] first = tailer.read(log, 0, 32 * 1024, false);
        byte[] second = tailer.read(log, first.length, 32 * 1024, false);
        assertEquals(32 * 1024 - 1, first.length);
        assertEquals("中\n", new String(second, StandardCharsets.UTF_8));
    }
}
