package com.ruoyi.train.core.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts finite scalar metrics from common JSON/Hugging Face training log lines. */
public final class TrainingMetricParser
{
    private static final Pattern VALUE = Pattern.compile("(?:^|[\\s,{])['\\\"]?([A-Za-z][A-Za-z0-9_.-]{0,63})['\\\"]?\\s*[:=]\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)");
    private static final Pattern STEP = Pattern.compile("(?:global_step/max_steps\\s*[=:]\\s*(\\d+)\\s*/\\s*\\d+|(?:global_step|step)['\\\"]?\\s*[:=]\\s*(\\d+))");

    private TrainingMetricParser() { }

    public static List<Value> parse(String line)
    {
        if (line == null || line.isBlank()) return List.of();
        Long step = null;
        Matcher stepMatcher = STEP.matcher(line);
        if (stepMatcher.find()) step = Long.valueOf(stepMatcher.group(1) != null ? stepMatcher.group(1) : stepMatcher.group(2));
        List<Value> result = new ArrayList<>();
        Matcher slash = Pattern.compile("global_step/max_steps\\s*[=:]\\s*(\\d+)\\s*/\\s*(\\d+)").matcher(line);
        if (slash.find())
        {
            result.add(new Value("global_step", Double.parseDouble(slash.group(1)), step));
            result.add(new Value("max_steps", Double.parseDouble(slash.group(2)), step));
        }
        Matcher matcher = VALUE.matcher(line);
        while (matcher.find())
        {
            double value;
            try { value = Double.parseDouble(matcher.group(2)); } catch (NumberFormatException invalid) { continue; }
            if (Double.isFinite(value)) result.add(new Value(matcher.group(1), value, step));
        }
        return List.copyOf(result);
    }

    public record Value(String name, double value, Long step) { }
}
