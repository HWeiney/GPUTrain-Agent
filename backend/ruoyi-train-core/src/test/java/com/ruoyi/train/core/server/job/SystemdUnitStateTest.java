package com.ruoyi.train.core.server.job;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SystemdUnitStateTest
{
    private static final String VALID = "LoadState=loaded\nActiveState=active\nSubState=running\nResult=success\n"
            + "ExecMainPID=42\nExecMainStatus=0\nControlGroup=/user.slice/job.service\n";

    @Test void parsesStateAndNumericFields()
    {
        var state = SystemdUnitState.parse(VALID);
        assertTrue(state.loaded());
        assertTrue(state.active());
        assertEquals(42L, state.mainPid());
        assertEquals(0, state.mainStatus());
    }

    @Test void rejectsDuplicateMissingMalformedAndOversizedFields()
    {
        assertThrows(IllegalArgumentException.class, () -> SystemdUnitState.parse(VALID + "ActiveState=inactive\n"));
        assertThrows(IllegalArgumentException.class, () -> SystemdUnitState.parse("LoadState=not-found\n"));
        assertThrows(IllegalArgumentException.class, () -> SystemdUnitState.parse(VALID.replace("ExecMainPID=42", "ExecMainPID=-1")));
        assertThrows(IllegalArgumentException.class, () -> SystemdUnitState.parse("x".repeat(16385)));
    }
}
