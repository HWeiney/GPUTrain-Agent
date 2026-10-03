package com.ruoyi.train.core.runner.web;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.DispatcherType;
import com.ruoyi.train.core.runner.security.RunnerRequestVerifier;
import com.ruoyi.train.core.runner.security.RunnerTransportSecurityGuard;
import com.ruoyi.train.core.runner.security.SignedRunnerRequest;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

@Component
public class RunnerAuthenticationInterceptor implements HandlerInterceptor
{
    public static final String RUNNER_ID_ATTRIBUTE = "gputrain.runnerId";
    private final RunnerRequestVerifier verifier;
    private final RunnerTransportSecurityGuard transportGuard;

    public RunnerAuthenticationInterceptor(RunnerRequestVerifier verifier, RunnerTransportSecurityGuard transportGuard)
    {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.transportGuard = Objects.requireNonNull(transportGuard, "transportGuard");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException
    {
        String path = path(request);
        if (!isSignedPath(path))
            return true;
        if (request.getDispatcherType() == DispatcherType.ASYNC)
        {
            if (request.getAttribute(RUNNER_ID_ATTRIBUTE) instanceof String authenticatedId
                    && !authenticatedId.isBlank())
                return true;
            RunnerProtocolExceptionHandler.writeError(response, invalid());
            return false;
        }
        try
        {
            transportGuard.assertAllowed(request.isSecure(), request.getRemoteAddr());
            byte[] body = (byte[]) request.getAttribute(CachedBodyHttpServletRequest.BODY_ATTRIBUTE);
            if (body == null)
                throw invalid();
            String runnerId = singleHeader(request, "X-Runner-Id");
            String nonce = singleHeader(request, "X-Runner-Nonce");
            String signature = singleHeader(request, "X-Runner-Signature");
            String timestampHeader = singleHeader(request, "X-Runner-Timestamp");
            long timestamp;
            try
            {
                timestamp = Long.parseLong(timestampHeader);
            }
            catch (NumberFormatException failure)
            {
                throw invalid();
            }
            String authenticatedId = verifier.verify(new SignedRunnerRequest(runnerId, timestamp,
                    nonce, signature, request.getMethod(), path, body));
            request.setAttribute(RUNNER_ID_ATTRIBUTE, authenticatedId);
            return true;
        }
        catch (RunnerProtocolException failure)
        {
            RunnerProtocolExceptionHandler.writeError(response, failure);
            return false;
        }
    }

    public static boolean isSignedPath(String path)
    {
        return path != null && path.startsWith("/runner-api/v1/")
                && !path.equals("/runner-api/v1/register");
    }

    private static String path(HttpServletRequest request)
    {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return uri.substring(context.length());
    }

    private static String singleHeader(HttpServletRequest request, String name)
    {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.get(0) == null || values.get(0).isBlank())
            throw invalid();
        return values.get(0);
    }

    private static RunnerProtocolException invalid()
    {
        return new RunnerProtocolException(RunnerErrorCode.RUNNER_AUTH_INVALID,
                "Runner request authentication failed");
    }
}
