package com.ruoyi.train.core.overview;

import org.springframework.stereotype.Service;

@Service
public class TrainOverviewService
{
    public TrainOverview getOverview()
    {
        return new TrainOverview("READY", 0, 0);
    }
}
