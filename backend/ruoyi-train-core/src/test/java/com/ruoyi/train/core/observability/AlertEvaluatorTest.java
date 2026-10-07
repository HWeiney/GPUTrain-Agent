package com.ruoyi.train.core.observability;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AlertEvaluatorTest
{
    private final AlertEvaluator evaluator = new AlertEvaluator();

    @Test void detectsKnownTrainingFailuresCaseInsensitively()
    {
        assertEquals("CUDA_OOM", evaluator.match("RuntimeError: CUDA out of memory"));
        assertEquals("NAN_LOSS", evaluator.match("train_loss=NaN"));
        assertEquals("NCCL_ERROR", evaluator.match("NCCL watchdog timeout"));
        assertNull(evaluator.match("global_step: 2, train_loss: 1.75"));
    }
}
