package com.ruoyi.train.core.observability;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TrainingMetricParserTest
{
    @Test void parsesHuggingFaceQuotedMetricMapsAndStep()
    {
        var values = TrainingMetricParser.parse("{'loss': 3.98, 'grad_norm': 1.2, 'learning_rate': 2e-5, 'epoch': 0.5, 'global_step': 7}");
        assertTrue(values.stream().anyMatch(value -> value.name().equals("loss") && value.value() == 3.98 && value.step() == 7));
        assertTrue(values.stream().anyMatch(value -> value.name().equals("learning_rate") && value.value() == 2e-5));
    }

    @Test void parsesStepSlashMaxStepsAndRejectsNonFiniteValues()
    {
        var values = TrainingMetricParser.parse("global_step/max_steps=2/10 train_loss=1.25");
        assertTrue(values.stream().anyMatch(value -> value.name().equals("train_loss") && value.step() == 2));
        assertEquals(2, values.stream().filter(value -> value.name().equals("global_step")).findFirst().orElseThrow().value());
        assertTrue(TrainingMetricParser.parse("loss=NaN").isEmpty());
    }
}
