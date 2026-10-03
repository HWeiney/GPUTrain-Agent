package com.ruoyi.train.runner.protocol;

public enum RunnerCommandStatus
{
    QUEUED, LEASED, RUNNING, SUCCEEDED, FAILED, STOPPED, REJECTED;

    public boolean isTerminal()
    {
        return this == SUCCEEDED || this == FAILED || this == STOPPED || this == REJECTED;
    }

    public void requireTransitionTo(RunnerCommandStatus next)
    {
        boolean allowed = switch (this) {
            case QUEUED -> next == LEASED || next == REJECTED;
            case LEASED -> next == RUNNING || next == REJECTED;
            case RUNNING -> next == SUCCEEDED || next == FAILED || next == STOPPED;
            default -> false;
        };
        if (!allowed) throw new IllegalStateException("Illegal command transition: " + this + " -> " + next);
    }
}
