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

    @RestController
    static class EnrollmentProtocolFailureController
    {
        @PostMapping("/test/enrollment-error")
        void fail()
        {
            throw new RunnerProtocolException(RunnerErrorCode.ENROLLMENT_INVALID, "secret exception detail");
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
