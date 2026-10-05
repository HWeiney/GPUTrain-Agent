package com.ruoyi.train.runner.gpu;

/** Raised when a command does not finish within the configured timeout. */
public class CommandTimeoutException extends RuntimeException
{
    public CommandTimeoutException(String message) { super(message); }
}
