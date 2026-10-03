package com.ruoyi.train.core.runner.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

/** Preserves the Runner protocol error contract before RuoYi's generic runtime handler. */
@RestControllerAdvice(basePackages = "com.ruoyi.train.core.runner.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RunnerProtocolExceptionHandler
{
    private static final String SAFE_MESSAGE = "Runner protocol request rejected.";

    @ExceptionHandler(RunnerProtocolException.class)
    public ResponseEntity<RunnerProtocolErrorResponse> handle(RunnerProtocolException exception)
    {
        HttpStatus status = exception.code() == RunnerErrorCode.RUNNER_HTTPS_REQUIRED
                ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new RunnerProtocolErrorResponse(exception.code().name(), SAFE_MESSAGE));
    }

    public record RunnerProtocolErrorResponse(String errorCode, String message)
    {
    }
}
