package com.ruoyi.train.core.runner.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import com.ruoyi.train.core.runner.service.RunnerHeartbeatService;
import com.ruoyi.train.runner.protocol.RunnerHeartbeat;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;
import com.ruoyi.train.core.runner.config.RunnerWebConfiguration;
import com.ruoyi.train.core.runner.domain.RunnerRecord;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;
import com.ruoyi.train.core.runner.security.RunnerCredentialCipher;
import com.ruoyi.train.core.runner.security.RunnerNonceStore;
import com.ruoyi.train.core.runner.security.RunnerRequestVerifier;
import com.ruoyi.train.runner.protocol.RunnerRequestSigner;

class RunnerAuthenticationInterceptorTest
{
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final byte[] SECRET = "runner-shared-secret".getBytes(StandardCharsets.UTF_8);
    private final Set<String> claimed = new HashSet<>();
    private final RunnerCredentialCipher cipher = new RunnerCredentialCipher(new byte[32]);
    private final RunnerMapper mapper = (RunnerMapper) Proxy.newProxyInstance(RunnerMapper.class.getClassLoader(),
            new Class<?>[] { RunnerMapper.class }, (proxy, method, args) ->
                    new RunnerRecord(1L, "runner", "linux", "/tmp", cipher.encrypt(SECRET),
                            "ACTIVE", null, NOW, NOW));
    private final RunnerNonceStore nonces = (id, nonce, ttl) -> claimed.add(id + ":" + nonce);
    private final RunnerRequestVerifier verifier = new RunnerRequestVerifier(mapper, cipher, nonces,
            new RunnerSecurityProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final RunnerAuthenticationInterceptor interceptor = new RunnerAuthenticationInterceptor(verifier);

    @Test
    void authenticatesExactBytesAndLeavesThemReadableForController() throws Exception
    {
        byte[] body = "{ \"text\":\"雪\" }".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest source = signedRequest("/runner-api/v1/heartbeat", body);
        CachedBodyHttpServletRequest request = new CachedBodyHttpServletRequest(source);
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals("1", request.getAttribute(RunnerAuthenticationInterceptor.RUNNER_ID_ATTRIBUTE));
        assertArrayEquals(body, request.getInputStream().readAllBytes());
        assertArrayEquals(body, request.getInputStream().readAllBytes());
    }

    @Test
    void rejectsMissingSignatureWithStableJsonError() throws Exception
    {
        MockHttpServletRequest source = new MockHttpServletRequest("POST", "/runner-api/v1/heartbeat");
        CachedBodyHttpServletRequest request = new CachedBodyHttpServletRequest(source);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"errorCode\":\"RUNNER_AUTH_INVALID\""));
    }

    @Test
    void skipsRegistrationAndOtherPaths() throws Exception
    {
        MockHttpServletRequest register = new MockHttpServletRequest("POST", "/runner-api/v1/register");
        MockHttpServletRequest unrelated = new MockHttpServletRequest("POST", "/train/runners/enrollments");
        assertTrue(interceptor.preHandle(register, new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(unrelated, new MockHttpServletResponse(), new Object()));
        assertEquals(Set.of(), claimed);
    }

    @Test
    void bodyFilterWrapsOnlySignedRunnerApiPaths() throws Exception
    {
        RunnerWebConfiguration config = new RunnerWebConfiguration(interceptor);
        byte[] body = "raw body".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest heartbeat = new MockHttpServletRequest("POST", "/runner-api/v1/heartbeat");
        heartbeat.setContent(body);
        MockFilterChain heartbeatChain = new MockFilterChain();
        config.runnerBodyCachingFilter().getFilter().doFilter(heartbeat, new MockHttpServletResponse(), heartbeatChain);
        assertTrue(heartbeatChain.getRequest() instanceof CachedBodyHttpServletRequest);
        assertArrayEquals(body, heartbeatChain.getRequest().getInputStream().readAllBytes());
        MockHttpServletRequest register = new MockHttpServletRequest("POST", "/runner-api/v1/register");
        MockFilterChain registerChain = new MockFilterChain();
        config.runnerBodyCachingFilter().getFilter().doFilter(register, new MockHttpServletResponse(), registerChain);
        assertEquals(register, registerChain.getRequest());
    }

    @Test
    void signedHeartbeatReachesControllerWithParsedBody() throws Exception
    {
        List<Object> received = new ArrayList<>();
        RunnerHeartbeatService service = new RunnerHeartbeatService(mapper, new org.springframework.data.redis.core.RedisTemplate<>())
        {
            @Override
            public void record(String runnerId, RunnerHeartbeat heartbeat, Instant receivedAt)
            {
                received.add(runnerId);
                received.add(heartbeat);
            }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new RunnerApiController(service))
                .addInterceptors(interceptor)
                .addFilters(new RunnerWebConfiguration(interceptor).runnerBodyCachingFilter().getFilter())
                .build();
        String json = "{ \"runnerId\":\"1\", \"linuxUsername\":\"linux\", "
                + "\"sentAt\":\"2026-10-03T12:00:00Z\" }";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        var builder = MockMvcRequestBuilders.post("/runner-api/v1/heartbeat")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Runner-Id", "1")
                .header("X-Runner-Timestamp", Long.toString(NOW.getEpochSecond()))
                .header("X-Runner-Nonce", "nonce-mvc")
                .header("X-Runner-Signature", RunnerRequestSigner.sign(SECRET,
                        RunnerRequestSigner.canonical("1", NOW.getEpochSecond(), "nonce-mvc", "POST",
                                "/runner-api/v1/heartbeat", body)));
        assertEquals(200, mvc.perform(builder).andReturn().getResponse().getStatus());
        assertEquals("1", received.get(0));
        assertEquals(new RunnerHeartbeat("1", "linux", NOW), received.get(1));
    }

    private static MockHttpServletRequest signedRequest(String path, byte[] body)
    {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContent(body);
        request.addHeader("X-Runner-Id", "1");
        request.addHeader("X-Runner-Timestamp", Long.toString(NOW.getEpochSecond()));
        request.addHeader("X-Runner-Nonce", "nonce-1");
        request.addHeader("X-Runner-Signature", RunnerRequestSigner.sign(SECRET,
                RunnerRequestSigner.canonical("1", NOW.getEpochSecond(), "nonce-1", "POST", path, body)));
        return request;
    }
}
