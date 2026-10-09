package com.ruoyi.train.core.server.web;

import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.train.core.server.ssh.SshGateway;

/** Secret-bearing requests must not reach generic handlers that log exception messages or JSON values. */
@RestControllerAdvice(assignableTypes=ServerAdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ServerApiExceptionHandler {
    private static final Set<String> SAFE_CODES=Set.of("DUPLICATE_SERVER_ADDRESS", "DUPLICATE_SERVER_ACCOUNT",
        "DUPLICATE_FINGERPRINT_CONFIRMATION_REQUIRED", "INVALID_SERVER_REQUEST", "SERVER_ACCOUNT_UNAVAILABLE",
        "CREDENTIAL_ROTATION_REQUIRED");
    @ExceptionHandler(Exception.class)
    public ResponseEntity<AjaxResult> handle(Exception failure) {
        int status=400;
        String code="INVALID_SERVER_REQUEST";
        if (failure instanceof AccessDeniedException || failure instanceof AuthenticationException) {
            status=403; code="ACCESS_DENIED";
        } else if (failure instanceof SshGateway.SshException ssh) {
            code=ssh.code().name();
        } else if (failure instanceof ServiceException && SAFE_CODES.contains(failure.getMessage())) {
            code=failure.getMessage();
        } else if (!(failure instanceof org.springframework.http.converter.HttpMessageNotReadableException)
            && !(failure instanceof IllegalArgumentException)
            && !(failure instanceof org.springframework.web.bind.ServletRequestBindingException)
            && !(failure instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException)) {
            status=500; code="SERVER_OPERATION_FAILED";
        }
        AjaxResult body=AjaxResult.error(status,code);
        body.put("errorCode",code);
        return ResponseEntity.status(status).body(body);
    }
}
