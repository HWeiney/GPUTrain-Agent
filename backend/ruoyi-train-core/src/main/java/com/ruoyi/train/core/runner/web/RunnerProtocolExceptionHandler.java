package com.ruoyi.train.core.runner.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import jakarta.servlet.http.HttpServletResponse;

/** Preserves the Runner protocol error contract before RuoYi's generic runtime handler. */
@RestControllerAdvice(basePackages = "com.ruoyi.train.core.runner.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RunnerProtocolExceptionHandler
{
    private static final String SAFE_MESSAGE = "Runner protocol request rejected.";

    @ExceptionHandler(RunnerProtocolException.class)
    public ResponseEntity<RunnerProtocolErrorResponse> handle(RunnerProtocolException exception)
    {
        return ResponseEntity.status(statusFor(exception.code()))
                .body(new RunnerProtocolErrorResponse(exception.code().name(), SAFE_MESSAGE));
    }

    public static void writeError(HttpServletResponse response, RunnerProtocolException exception) throws IOException
    {
        response.setStatus(statusFor(exception.code()).value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"errorCode\":\"" + exception.code().name()
                + "\",\"message\":\"" + SAFE_MESSAGE + "\"}");
    }

    private static HttpStatus statusFor(RunnerErrorCode code)
    {
        return switch (code)
        {
            case RUNNER_HTTPS_REQUIRED -> HttpStatus.FORBIDDEN;
            case RUNNER_TEMPORARILY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RUNNER_REQUEST_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case COMMAND_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case COMMAND_LEASE_EXPIRED, COMMAND_STATUS_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    public record RunnerProtocolErrorResponse(String errorCode, String message)
    {
    }
}
