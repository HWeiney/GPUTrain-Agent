package com.ruoyi.train.core.overview;

public record TrainOverview(String status, int activeExperiments, int onlineRunners, int onlineServers)
{
    public TrainOverview(String status, int activeExperiments, int onlineRunners) {
        this(status, activeExperiments, onlineRunners, 0);
    }
}
