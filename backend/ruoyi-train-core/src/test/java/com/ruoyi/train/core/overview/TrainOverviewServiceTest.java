package com.ruoyi.train.core.overview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TrainOverviewServiceTest
{
    @Test
    void startsWithAnEmptyReadyOverview()
    {
        TrainOverview overview = new TrainOverviewService().getOverview();

        assertEquals("READY", overview.status());
        assertEquals(0, overview.activeExperiments());
        assertEquals(0, overview.onlineRunners());
    }
}
