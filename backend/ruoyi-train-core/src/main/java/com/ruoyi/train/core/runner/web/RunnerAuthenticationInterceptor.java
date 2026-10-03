package com.ruoyi.train.core.runner.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.ruoyi.train.core.runner.security.RunnerRequestVerifier;
import com.ruoyi.train.core.runner.security.SignedRunnerRequest;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

@Component
public class RunnerAuthenticationInterceptor implements HandlerInterceptor
{
    public static final String RUNNER_ID_ATTRIBUTE = "gputrain.runnerId";
    private final RunnerRequestVerifier verifier;

    public RunnerAuthenticationInterceptor(RunnerRequestVerifier verifier)
    {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException
    {
        String path = path(request);
        if (!isSignedPath(path))
            return true;
        try
        {
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
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"errorCode\":\"" + failure.code().name()
                    + "\",\"message\":\"Runner protocol request rejected.\"}");
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
