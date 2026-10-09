package com.ruoyi.train.core.server.job;

import java.util.HashMap;
import java.util.Map;

public record SystemdUnitState(String loadState, String activeState, String subState,
        String result, Long mainPid, Integer mainStatus, String controlGroup)
{
    private static final java.util.Set<String> REQUIRED = java.util.Set.of("LoadState", "ActiveState", "SubState",
            "Result", "ExecMainPID", "ExecMainStatus", "ControlGroup");

    public static SystemdUnitState parse(String output)
    {
        if (output == null || output.length() > 16384) throw invalid();
        Map<String, String> values = new HashMap<>();
        for (String line : output.lines().toList())
        {
            int separator = line.indexOf('=');
            if (separator < 1 || values.put(line.substring(0, separator), line.substring(separator + 1)) != null)
                throw invalid();
        }
        if (!values.keySet().containsAll(REQUIRED)) throw invalid();
        return new SystemdUnitState(values.get("LoadState"), values.get("ActiveState"), values.get("SubState"),
                values.get("Result"), nonNegativeLong(values.get("ExecMainPID")),
                nonNegativeInt(values.get("ExecMainStatus")), values.get("ControlGroup"));
    }

    public boolean loaded() { return "loaded".equals(loadState); }
    public boolean active() { return "active".equals(activeState); }

    private static Long nonNegativeLong(String value)
    {
        try { long number = Long.parseLong(value); if (number < 0) throw invalid(); return number; }
        catch (NumberFormatException e) { throw invalid(); }
    }

    private static Integer nonNegativeInt(String value)
    {
        try { int number = Integer.parseInt(value); if (number < 0) throw invalid(); return number; }
        catch (NumberFormatException e) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("CORRUPT_SYSTEMD_STATE"); }
}
