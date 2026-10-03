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
import jakarta.servlet.ServletInputStream;
import com.ruoyi.train.core.runner.service.RunnerHeartbeatService;
import com.ruoyi.train.runner.protocol.RunnerHeartbeat;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;
import com.ruoyi.train.core.runner.config.RunnerWebConfiguration;
import com.ruoyi.train.core.runner.domain.RunnerRecord;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;
import com.ruoyi.train.core.runner.security.RunnerCredentialCipher;
import com.ruoyi.train.core.runner.security.RunnerNonceStore;
import com.ruoyi.train.core.runner.security.RunnerRequestVerifier;
import com.ruoyi.train.core.runner.security.RunnerTransportSecurityGuard;
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
    private final RunnerAuthenticationInterceptor interceptor = new RunnerAuthenticationInterceptor(verifier,
            new RunnerTransportSecurityGuard(new RunnerSecurityProperties()));

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
        source.setSecure(true);
        CachedBodyHttpServletRequest request = new CachedBodyHttpServletRequest(source);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"errorCode\":\"RUNNER_AUTH_INVALID\""));
    }

    @Test
    void correctlySignedExternalHttpHeartbeatRequiresHttps() throws Exception
    {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest source = signedRequest("/runner-api/v1/heartbeat", body);
        source.setSecure(false);
        source.setRemoteAddr("192.0.2.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new CachedBodyHttpServletRequest(source), response, new Object()));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"errorCode\":\"RUNNER_HTTPS_REQUIRED\""));
        assertEquals(Set.of(), claimed);
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
        RunnerWebConfiguration config = new RunnerWebConfiguration(interceptor, new RunnerSecurityProperties());
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
    void declaredOversizedBodyIsRejectedBeforeReading() throws Exception
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        properties.setMaxRequestBodyBytes(4);
        RunnerWebConfiguration config = new RunnerWebConfiguration(interceptor, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/runner-api/v1/heartbeat")
        {
            @Override
            public ServletInputStream getInputStream() { throw new AssertionError("body was read"); }
        };
        request.setContent(new byte[5]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        config.runnerBodyCachingFilter().getFilter().doFilter(request, response, chain);
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"errorCode\":\"RUNNER_REQUEST_TOO_LARGE\""));
    }

    @Test
    void unknownLengthBodyIsBoundedAndReturnsSafe413() throws Exception
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        properties.setMaxRequestBodyBytes(4);
        RunnerWebConfiguration config = new RunnerWebConfiguration(interceptor, properties);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/runner-api/v1/heartbeat")
        {
            @Override
            public int getContentLength() { return -1; }
            @Override
            public long getContentLengthLong() { return -1; }
        };
        request.setContent(new byte[5]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        config.runnerBodyCachingFilter().getFilter().doFilter(request, response, new MockFilterChain());
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"errorCode\":\"RUNNER_REQUEST_TOO_LARGE\""));
    }

    @Test
    void exactlyConfiguredBodyLimitIsAcceptedAndRegistrationIsExcluded() throws Exception
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        properties.setMaxRequestBodyBytes(4);
        RunnerWebConfiguration config = new RunnerWebConfiguration(interceptor, properties);
        MockHttpServletRequest heartbeat = new MockHttpServletRequest("POST", "/runner-api/v1/heartbeat");
        heartbeat.setContent("1234".getBytes(StandardCharsets.UTF_8));
        MockFilterChain heartbeatChain = new MockFilterChain();
        config.runnerBodyCachingFilter().getFilter().doFilter(heartbeat,
                new MockHttpServletResponse(), heartbeatChain);
        assertTrue(heartbeatChain.getRequest() instanceof CachedBodyHttpServletRequest);
        assertArrayEquals("1234".getBytes(StandardCharsets.UTF_8),
                heartbeatChain.getRequest().getInputStream().readAllBytes());

        MockHttpServletRequest register = new MockHttpServletRequest("POST", "/runner-api/v1/register");
        register.setContent("12345".getBytes(StandardCharsets.UTF_8));
        MockFilterChain registerChain = new MockFilterChain();
        config.runnerBodyCachingFilter().getFilter().doFilter(register,
                new MockHttpServletResponse(), registerChain);
        assertEquals(register, registerChain.getRequest());
    }

    @Test
    void explicitlyAllowedLoopbackHttpStillAuthenticates() throws Exception
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        properties.setRequireHttps(false);
        properties.setAllowLoopbackHttp(true);
        RunnerAuthenticationInterceptor localInterceptor = new RunnerAuthenticationInterceptor(verifier,
                new RunnerTransportSecurityGuard(properties));
        MockHttpServletRequest source = signedRequest("/runner-api/v1/heartbeat", "{}".getBytes(StandardCharsets.UTF_8));
        source.setSecure(false);
        source.setRemoteAddr("127.0.0.1");
        assertTrue(localInterceptor.preHandle(new CachedBodyHttpServletRequest(source),
                new MockHttpServletResponse(), new Object()));
        assertEquals(Set.of("1:nonce-1"), claimed);
    }

    @Test
    void oversizedBodyReturnsStableErrorThroughMvcFilter() throws Exception
    {
        RunnerSecurityProperties properties = new RunnerSecurityProperties();
        properties.setMaxRequestBodyBytes(4);
        var mvc = MockMvcBuilders.standaloneSetup(new RunnerApiController(null))
                .addInterceptors(interceptor)
                .addFilters(new RunnerWebConfiguration(interceptor, properties).runnerBodyCachingFilter().getFilter())
                .build();
        var result = mvc.perform(MockMvcRequestBuilders.post("/runner-api/v1/heartbeat")
                .secure(true).contentType(MediaType.APPLICATION_JSON).content("12345"))
                .andReturn();
        assertEquals(413, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString()
                .contains("\"errorCode\":\"RUNNER_REQUEST_TOO_LARGE\""));
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
                .addFilters(new RunnerWebConfiguration(interceptor, new RunnerSecurityProperties())
                        .runnerBodyCachingFilter().getFilter())
                .build();
        String json = "{ \"runnerId\":\"1\", \"linuxUsername\":\"linux\", "
                + "\"sentAt\":\"2026-10-03T12:00:00Z\" }";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        var builder = MockMvcRequestBuilders.post("/runner-api/v1/heartbeat")
                .secure(true)
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
        request.setSecure(true);
        request.setContent(body);
        request.addHeader("X-Runner-Id", "1");
        request.addHeader("X-Runner-Timestamp", Long.toString(NOW.getEpochSecond()));
        request.addHeader("X-Runner-Nonce", "nonce-1");
        request.addHeader("X-Runner-Signature", RunnerRequestSigner.sign(SECRET,
                RunnerRequestSigner.canonical("1", NOW.getEpochSecond(), "nonce-1", "POST", path, body)));
        return request;
    }
}
