package com.ruoyi.train.core.observability;

import java.util.Locale;
import java.util.regex.Pattern;

/** Small deterministic rules; alerts never invoke a model or run an action. */
public final class AlertEvaluator
{
    private static final Pattern OOM = Pattern.compile("(?:cuda|gpu).{0,80}out of memory|out of memory.{0,80}(?:cuda|gpu)");
    private static final Pattern NAN = Pattern.compile("(?:train_)?loss\\s*[:=]\\s*(?:nan|[-+]?inf(?:inity)?)\\b");
    private static final Pattern NCCL = Pattern.compile("nccl.{0,120}(?:error|timeout|failed)|(?:error|timeout|failed).{0,120}nccl");

    public String match(String line)
    {
        String value = line == null ? "" : line.toLowerCase(Locale.ROOT);
        if (OOM.matcher(value).find()) return "CUDA_OOM";
        if (NAN.matcher(value).find()) return "NAN_LOSS";
        if (NCCL.matcher(value).find()) return "NCCL_ERROR";
        return null;
    }
}
