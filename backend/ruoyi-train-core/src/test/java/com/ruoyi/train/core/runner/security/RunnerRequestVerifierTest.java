package com.ruoyi.train.core.runner.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;
import com.ruoyi.train.core.runner.domain.RunnerRecord;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import com.ruoyi.train.runner.protocol.RunnerRequestSigner;

class RunnerRequestVerifierTest
{
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] STORED_SECRET = Base64.getUrlEncoder().withoutPadding().encode(SECRET);
    private final Set<String> claimed = new HashSet<>();
    private final RunnerCredentialCipher cipher = new RunnerCredentialCipher(new byte[32]);
    private final RunnerRecord runner = new RunnerRecord(1L, "runner", "linux", "/tmp",
            cipher.encrypt(STORED_SECRET), "ACTIVE", null, NOW, NOW);
    private final RunnerMapper mapper = (RunnerMapper) Proxy.newProxyInstance(RunnerMapper.class.getClassLoader(),
            new Class<?>[] { RunnerMapper.class }, (proxy, method, args) ->
                    method.getName().equals("findById") ? runner : null);
    private final RunnerNonceStore nonceStore = (id, nonce, ttl) -> claimed.add(id + ":" + nonce);
    private final RunnerSecurityProperties properties = new RunnerSecurityProperties();
    private final RunnerRequestVerifier verifier = new RunnerRequestVerifier(mapper, cipher, nonceStore,
            properties, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsValidSignedRequestAndClaimsNonce()
    {
        assertEquals("1", verifier.verify(signed("nonce-1", NOW, "{}")));
        assertEquals(Set.of("1:nonce-1"), claimed);
    }

    @Test
    void rejectsReplayedNonceAfterValidSignature()
    {
        SignedRunnerRequest request = signed("nonce-1", NOW, "{}");
        assertEquals("1", verifier.verify(request));
        assertEquals(RunnerErrorCode.RUNNER_REPLAY_DETECTED,
                assertThrows(RunnerProtocolException.class, () -> verifier.verify(request)).code());
    }

    @Test
    void rejectsModifiedBodyWithoutClaimingNonce()
    {
        SignedRunnerRequest request = signed("nonce-2", NOW, "{}");
        SignedRunnerRequest changed = new SignedRunnerRequest(request.runnerId(), request.timestamp(),
                request.nonce(), request.signature(), request.method(), request.path(),
                "{\"changed\":true}".getBytes(StandardCharsets.UTF_8));
        assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID,
                assertThrows(RunnerProtocolException.class, () -> verifier.verify(changed)).code());
        assertEquals(Set.of(), claimed);
    }

    @Test
    void rejectsClockSkewWithoutClaimingNonce()
    {
        assertEquals(RunnerErrorCode.RUNNER_CLOCK_SKEW,
                assertThrows(RunnerProtocolException.class,
                        () -> verifier.verify(signed("old", NOW.minus(Duration.ofMinutes(3)), "{}"))).code());
        assertEquals(Set.of(), claimed);
    }

    @Test
    void signedRequestDefensivelyCopiesBody()
    {
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        SignedRunnerRequest request = signed("nonce-3", NOW, "{}");
        SignedRunnerRequest copy = new SignedRunnerRequest(request.runnerId(), request.timestamp(),
                request.nonce(), request.signature(), request.method(), request.path(), bytes);
        bytes[0] = 'x';
        byte[] returned = copy.body();
        returned[1] = 'x';
        assertArrayEquals("{}".getBytes(StandardCharsets.UTF_8), copy.body());
        assertFalse(copy.toString().contains(copy.signature()));
    }

    @Test
    void redisNonceClaimIsAtomicAndIncludesTtl()
    {
        List<Object> arguments = new ArrayList<>();
        RedisRunnerNonceStore store = new RedisRunnerNonceStore(redis((method, args) -> {
            arguments.addAll(List.of(args));
            return true;
        }));
        assertEquals(true, store.claim("1", "nonce", Duration.ofMinutes(5)));
        assertEquals(List.of("gputrain:runner:nonce:1:nonce", "1", Duration.ofMinutes(5)), arguments);
    }

    @Test
    void redisFailureFailsClosedWithoutExposingDetails()
    {
        RedisRunnerNonceStore store = new RedisRunnerNonceStore(redis((method, args) -> {
            throw new IllegalStateException("redis-connection-secret");
        }));
        RunnerProtocolException failure = assertThrows(RunnerProtocolException.class,
                () -> store.claim("1", "nonce", Duration.ofMinutes(5)));
        assertEquals(RunnerErrorCode.RUNNER_AUTH_INVALID, failure.code());
        assertEquals(false, failure.getMessage().contains("redis-connection-secret"));
    }

    private static RedisTemplate<Object, Object> redis(RedisValueCall call)
    {
        @SuppressWarnings("unchecked")
        ValueOperations<Object, Object> values = (ValueOperations<Object, Object>) Proxy.newProxyInstance(
                ValueOperations.class.getClassLoader(), new Class<?>[] { ValueOperations.class },
                (proxy, method, args) -> call.apply(method.getName(), args));
        return new RedisTemplate<>()
        {
            @Override
            public ValueOperations<Object, Object> opsForValue() { return values; }
        };
    }

    private interface RedisValueCall
    {
        Object apply(String method, Object[] args);
    }

    private static SignedRunnerRequest signed(String nonce, Instant at, String body)
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String signature = RunnerRequestSigner.sign(SECRET, RunnerRequestSigner.canonical(
                "1", at.getEpochSecond(), nonce, "POST", "/runner-api/v1/heartbeat", bytes));
        return new SignedRunnerRequest("1", at.getEpochSecond(), nonce, signature,
                "POST", "/runner-api/v1/heartbeat", bytes);
    }
}
