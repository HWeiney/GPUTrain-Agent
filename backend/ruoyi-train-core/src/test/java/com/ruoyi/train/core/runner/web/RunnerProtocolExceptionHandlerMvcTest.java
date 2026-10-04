package com.ruoyi.train.core.runner.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;
import com.ruoyi.train.core.runner.security.RunnerTransportSecurityGuard;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import com.ruoyi.train.core.runner.service.RunnerCommandService;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.util.UUID;

class RunnerProtocolExceptionHandlerMvcTest
{
    @Test
    void insecureExternalHttpReturnsStableProtocolErrorEnvelope() throws Exception
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        properties.setRequireHttps(false);
        properties.setAllowLoopbackHttp(true);
        RunnerTransportSecurityGuard guard = new RunnerTransportSecurityGuard(properties);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RunnerRegistrationController(null, guard))
                .setControllerAdvice(new RunnerProtocolExceptionHandler(), new GenericRuntimeExceptionAdvice())
                .build();

        MvcResult result = mvc.perform(post("/runner-api/v1/register")
                .with(request -> { request.setSecure(false); request.setScheme("http"); request.setRemoteAddr("192.0.2.9"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enrollmentId\":1,\"enrollmentToken\":\"example\",\"runnerName\":\"runner\",\"linuxUsername\":\"user\"}"))
                .andReturn();
        assertEquals(403, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentType().contains("application/json"));
        assertTrue(result.getResponse().getContentAsString().contains("\"errorCode\":\"RUNNER_HTTPS_REQUIRED\""));
        assertTrue(result.getResponse().getContentAsString().contains("Runner protocol request rejected."));
    }

    @Test
    void enrollmentProtocolErrorsAreStableAndDoNotExposeExceptionDetails() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EnrollmentProtocolFailureController())
                .setControllerAdvice(new RunnerProtocolExceptionHandler(), new GenericRuntimeExceptionAdvice())
                .build();

        MvcResult result = mvc.perform(post("/test/enrollment-error"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();
        String response = result.getResponse().getContentAsString();
        assertTrue(response.contains("\"errorCode\":\"ENROLLMENT_INVALID\""));
        assertTrue(response.contains("Runner protocol request rejected."));
        assertFalse(response.contains("secret"));
    }

    @Test
    void unavailableProtocolErrorsReturnSafe503() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UnavailableProtocolFailureController())
                .setControllerAdvice(new RunnerProtocolExceptionHandler(), new GenericRuntimeExceptionAdvice())
                .build();
        MvcResult result = mvc.perform(post("/test/runner-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        String response = result.getResponse().getContentAsString();
        assertTrue(response.contains("\"errorCode\":\"RUNNER_TEMPORARILY_UNAVAILABLE\""));
        assertTrue(response.contains("Runner protocol request rejected."));
        assertFalse(response.contains("redis-internal-secret"));
    }

    @Test
    void oversizedProtocolErrorsReturnSafe413() throws Exception
    {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TooLargeProtocolFailureController())
                .setControllerAdvice(new RunnerProtocolExceptionHandler()).build();
        MvcResult result = mvc.perform(post("/test/runner-too-large"))
                .andExpect(status().isPayloadTooLarge()).andReturn();
        assertTrue(result.getResponse().getContentAsString()
                .contains("\"errorCode\":\"RUNNER_REQUEST_TOO_LARGE\""));
        assertFalse(result.getResponse().getContentAsString().contains("internal-detail"));
    }

    @Test
    void signedReportErrorsUseStableStatusAndHideInternalDetail() throws Exception
    {
        assertReportError(RunnerErrorCode.COMMAND_NOT_FOUND, 404);
        assertReportError(RunnerErrorCode.COMMAND_LEASE_EXPIRED, 409);
        assertReportError(RunnerErrorCode.COMMAND_STATUS_CONFLICT, 409);
    }

    private void assertReportError(RunnerErrorCode code, int expectedStatus) throws Exception
    {
        RunnerCommandMapper mapper = (RunnerCommandMapper) Proxy.newProxyInstance(
                RunnerCommandMapper.class.getClassLoader(), new Class<?>[] { RunnerCommandMapper.class },
                (proxy, method, args) -> null);
        RunnerCommandService service = new RunnerCommandService(mapper, new ObjectMapper())
        {
            @Override
            public com.ruoyi.train.core.runner.domain.RunnerCommandRecord report(String runnerId,
                    UUID commandId, com.ruoyi.train.runner.protocol.RunnerCommandReport report)
            {
                throw new RunnerProtocolException(code, "internal database secret");
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RunnerApiController(null, service, null))
                .setControllerAdvice(new RunnerProtocolExceptionHandler(), new GenericRuntimeExceptionAdvice())
                .build();
        MvcResult result = mvc.perform(post("/runner-api/v1/commands/{id}/reports", UUID.randomUUID())
                .requestAttr(RunnerAuthenticationInterceptor.RUNNER_ID_ATTRIBUTE, "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"previousStatus\":\"LEASED\",\"status\":\"RUNNING\",\"attempt\":1}"))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus());
        String response = result.getResponse().getContentAsString();
        assertTrue(response.contains("\"errorCode\":\"" + code.name() + "\""));
        assertFalse(response.contains("internal database secret"));
    }

    @RestController
    static class EnrollmentProtocolFailureController
    {
        @PostMapping("/test/enrollment-error")
        void fail()
        {
            throw new RunnerProtocolException(RunnerErrorCode.ENROLLMENT_INVALID, "secret exception detail");
        }
    }

    @RestController
    static class UnavailableProtocolFailureController
    {
        @PostMapping("/test/runner-unavailable")
        void fail()
        {
            throw new RunnerProtocolException(RunnerErrorCode.RUNNER_TEMPORARILY_UNAVAILABLE,
                    "redis-internal-secret");
        }
    }

    @RestController
    static class TooLargeProtocolFailureController
    {
        @PostMapping("/test/runner-too-large")
        void fail()
        {
            throw new RunnerProtocolException(RunnerErrorCode.RUNNER_REQUEST_TOO_LARGE,
                    "internal-detail");
        }
    }

    @RestControllerAdvice
    @Order(Ordered.LOWEST_PRECEDENCE)
    static class GenericRuntimeExceptionAdvice
    {
        @ExceptionHandler(RuntimeException.class)
        AjaxResult handle(RuntimeException exception)
        {
            return AjaxResult.error(exception.getMessage());
        }
    }
}
