package com.ruoyi.train.runner;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FixtureMainTest
{
    @Test void sleepPrintsItsPidAndRejectsUnexpectedArguments() throws Exception
    {
        var command = new ArrayList<String>(); command.add(ProcessSupervisorTest.java());
        command.addAll(ProcessSupervisorTest.args("--mode", "sleep", "--seconds", "0"));
        Process p = new ProcessBuilder(command).start();
        assertTrue(p.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, p.exitValue());
        assertEquals(p.pid() + "\n", new String(p.getInputStream().readAllBytes()));
        command.add("unexpected"); p = new ProcessBuilder(command).start();
        assertTrue(p.waitFor(5, TimeUnit.SECONDS)); assertEquals(2, p.exitValue());
    }
    @Test void deterministicModes() throws Exception
    {
        for (String mode : List.of("success", "failure", "invalid"))
        {
            var command = new ArrayList<String>(); command.add(ProcessSupervisorTest.java());
            command.addAll(ProcessSupervisorTest.args("--mode", mode));
            Process p = new ProcessBuilder(command).start();
            assertTrue(p.waitFor(5, TimeUnit.SECONDS));
            assertEquals(mode.equals("success") ? 0 : mode.equals("failure") ? 17 : 2, p.exitValue());
            assertEquals(mode.equals("success") ? "fixture success\n" : "", new String(p.getInputStream().readAllBytes()));
            if (mode.equals("failure")) assertEquals("fixture failure\n", new String(p.getErrorStream().readAllBytes()));
        }
    }
}
