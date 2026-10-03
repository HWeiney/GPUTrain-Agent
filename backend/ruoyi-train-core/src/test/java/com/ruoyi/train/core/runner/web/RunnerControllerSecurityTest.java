package com.ruoyi.train.core.runner.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import jakarta.servlet.http.HttpServletRequest;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;
import com.ruoyi.train.core.runner.security.RunnerTransportSecurityGuard;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import com.ruoyi.train.runner.protocol.RunnerRegistration;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunnerControllerSecurityTest
{
    @Test
    void enrollmentEndpointRequiresRunnerEnrollPermission()
    {
        assertEquals("/train/runners/enrollments", RunnerAdminController.class
                .getAnnotation(RequestMapping.class).value()[0]);
        Method method = method(RunnerAdminController.class, "createEnrollment");
        assertEquals("@ss.hasPermi('train:runner:enroll')", method.getAnnotation(PreAuthorize.class).value());
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
