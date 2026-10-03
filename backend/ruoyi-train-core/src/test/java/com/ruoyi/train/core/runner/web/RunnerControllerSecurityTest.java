package com.ruoyi.train.core.runner.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.servlet.http.HttpServletRequest;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;
import com.ruoyi.train.core.runner.security.RunnerTransportSecurityGuard;
import com.ruoyi.train.core.runner.service.RunnerCommandService;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.runner.protocol.RunnerCommandLease;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import com.ruoyi.train.runner.protocol.RunnerRegistration;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunnerControllerSecurityTest
{
    @Test
    void enrollmentEndpointRequiresRunnerEnrollPermission()
    {
        assertEquals("/train/runners", RunnerAdminController.class
                .getAnnotation(RequestMapping.class).value()[0]);
        Method method = method(RunnerAdminController.class, "createEnrollment");
        assertEquals("@ss.hasPermi('train:runner:enroll')", method.getAnnotation(PreAuthorize.class).value());
        assertEquals("/enrollments", method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class)
                .value()[0]);
    }

    @Test
    void commandManagementRequiresCommandPermission()
    {
        for (String name : new String[] { "createFixtureCommand", "requestStop" })
        {
            Method method = method(RunnerAdminController.class, name);
            assertEquals("@ss.hasPermi('train:runner:command')", method.getAnnotation(PreAuthorize.class).value());
        }
    }

    @Test
    void commandRunnerEndpointsRequireSignedIdentity()
    {
        for (String name : new String[] { "poll", "report" })
        {
            Method method = method(RunnerApiController.class, name);
            assertNotNull(method.getAnnotation(Anonymous.class));
            assertTrue(java.util.Arrays.stream(method.getParameters()).anyMatch(parameter ->
                    parameter.getAnnotation(org.springframework.web.bind.annotation.RequestAttribute.class) != null));
        }
        assertTrue(RunnerAuthenticationInterceptor.isSignedPath("/runner-api/v1/commands/poll"));
        assertTrue(RunnerAuthenticationInterceptor.isSignedPath("/runner-api/v1/commands/id/reports"));
    }

    @Test
    void pollReturnsNoContentImmediatelyForZeroWaitAndCapsLongWait() throws Exception
    {
        RunnerCommandMapper mapper = (RunnerCommandMapper) Proxy.newProxyInstance(
                RunnerCommandMapper.class.getClassLoader(), new Class<?>[] { RunnerCommandMapper.class },
                (proxy, method, args) -> null);
        RunnerCommandService empty = new RunnerCommandService(mapper, new ObjectMapper())
        {
            @Override public Optional<RunnerCommandLease> leaseNext(String runnerId, Instant now, Duration duration)
            { return Optional.empty(); }
        };
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        try
        {
            RunnerApiController controller = new RunnerApiController(null, empty, scheduler);
            var immediate = controller.poll("1", 0);
            assertEquals(204, ((org.springframework.http.ResponseEntity<?>) immediate.getResult())
                    .getStatusCode().value());
            var bounded = controller.poll("1", 99);
            assertEquals(Long.valueOf(20_000L), org.springframework.test.util.ReflectionTestUtils
                    .invokeMethod(bounded, "getTimeoutValue"));
            var timeout = (org.springframework.web.context.request.async.DeferredResultProcessingInterceptor)
                    org.springframework.test.util.ReflectionTestUtils.invokeMethod(bounded, "getLifecycleInterceptor");
            timeout.handleTimeout(null, bounded);
            assertEquals(204, ((org.springframework.http.ResponseEntity<?>) bounded.getResult())
                    .getStatusCode().value());
        }
        finally { scheduler.shutdownNow(); }
    }

    @Test
    void registrationEndpointIsAnonymous()
    {
        assertEquals("/runner-api/v1/register", RunnerRegistrationController.class
                .getAnnotation(RequestMapping.class).value()[0]);
        assertNotNull(method(RunnerRegistrationController.class, "register").getAnnotation(Anonymous.class));
    }

    @Test
    void bothCredentialEndpointsApplyTransportGuard()
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        properties.setRequireHttps(false);
        properties.setAllowLoopbackHttp(true);
        RunnerTransportSecurityGuard guard = new RunnerTransportSecurityGuard(properties);
        HttpServletRequest externalHttp = request(false, "192.0.2.9");

        RunnerProtocolException adminError = assertThrows(RunnerProtocolException.class,
                () -> new RunnerAdminController(null, guard).createEnrollment(
                        new RunnerAdminController.CreateEnrollmentRequest(1L, "runner", "user", "/tmp",
                                Instant.now().plusSeconds(300), "admin-request-key"), externalHttp));
        RunnerProtocolException registrationError = assertThrows(RunnerProtocolException.class,
                () -> new RunnerRegistrationController(null, guard).register(
                        new RunnerRegistration(1L, "token", "runner", "user"), externalHttp));
        assertEquals(RunnerErrorCode.RUNNER_HTTPS_REQUIRED, adminError.code());
        assertEquals(adminError.code(), registrationError.code());
    }

    private static Method method(Class<?> type, String name)
    {
        return java.util.Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private static HttpServletRequest request(boolean secure, String remoteAddress)
    {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> switch (method.getName())
                {
                    case "isSecure" -> secure;
                    case "getRemoteAddr" -> remoteAddress;
                    default -> null;
                });
    }
}
