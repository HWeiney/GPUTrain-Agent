package com.ruoyi.train.core.runner.security;

import java.security.MessageDigest;
import java.util.Arrays;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;
import com.ruoyi.train.core.runner.domain.RunnerRecord;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import com.ruoyi.train.runner.protocol.RunnerRequestSigner;

@Component
public class RunnerRequestVerifier
{
    private final RunnerMapper runnerMapper;
    private final RunnerCredentialCipher cipher;
    private final RunnerNonceStore nonceStore;
    private final Duration allowedSkew;
    private final Duration nonceTtl;
    private final Clock clock;

    @Autowired
    public RunnerRequestVerifier(RunnerMapper runnerMapper, RunnerCredentialCipher cipher,
            RunnerNonceStore nonceStore, RunnerSecurityProperties properties)
    {
        this(runnerMapper, cipher, nonceStore, properties, Clock.systemUTC());
    }

    public RunnerRequestVerifier(RunnerMapper runnerMapper, RunnerCredentialCipher cipher,
            RunnerNonceStore nonceStore, RunnerSecurityProperties properties, Clock clock)
    {
        this.runnerMapper = Objects.requireNonNull(runnerMapper, "runnerMapper");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.nonceStore = Objects.requireNonNull(nonceStore, "nonceStore");
        this.allowedSkew = DurationStyle.detectAndParse(properties.getAllowedClockSkew());
        this.nonceTtl = DurationStyle.detectAndParse(properties.getNonceTtl());
        if (allowedSkew.isNegative() || nonceTtl.isNegative() || nonceTtl.isZero())
            throw new IllegalArgumentException("Invalid Runner security durations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public String verify(SignedRunnerRequest request)
    {
        if (request == null || request.runnerId() == null || request.nonce() == null
                || request.signature() == null || request.method() == null || request.path() == null
                || !request.runnerId().matches("[1-9][0-9]{0,18}")
                || !request.nonce().matches("[A-Za-z0-9_-]{1,128}"))
            throw invalid();
        long runnerId;
        try
        {
            runnerId = Long.parseLong(request.runnerId());
        }
        catch (NumberFormatException failure)
        {
            throw invalid();
        }
        long now = clock.instant().getEpochSecond();
        long skew = allowedSkew.getSeconds();
        if (request.timestamp() < now - skew || request.timestamp() > now + skew)
            throw new RunnerProtocolException(RunnerErrorCode.RUNNER_CLOCK_SKEW,
                    "Runner request timestamp is outside the allowed window");

        RunnerRecord runner = runnerMapper.findById(runnerId);
        if (runner == null || !"ACTIVE".equals(runner.status()) || runner.secretCiphertext() == null)
            throw invalid();
        byte[] encodedSecret;
        try
        {
            encodedSecret = cipher.decrypt(runner.secretCiphertext());
        }
        catch (IllegalArgumentException failure)
        {
            throw invalid();
        }
        byte[] secret;
        try
        {
            secret = Base64.getUrlDecoder().decode(encodedSecret);
        }
        catch (IllegalArgumentException failure)
        {
            throw invalid();
        }
        finally
        {
            Arrays.fill(encodedSecret, (byte) 0);
        }
        if (secret.length != 32)
        {
            Arrays.fill(secret, (byte) 0);
            throw invalid();
        }
        byte[] expected;
        try
        {
            String canonical = RunnerRequestSigner.canonical(request.runnerId(), request.timestamp(),
                    request.nonce(), request.method(), request.path(), request.body());
            expected = Base64.getUrlDecoder().decode(RunnerRequestSigner.sign(secret, canonical));
        }
        finally
        {
            Arrays.fill(secret, (byte) 0);
        }
        byte[] received;
        try
        {
            received = Base64.getUrlDecoder().decode(request.signature());
        }
        catch (IllegalArgumentException failure)
        {
            throw invalid();
        }
        if (!MessageDigest.isEqual(expected, received))
            throw invalid();
        if (!nonceStore.claim(request.runnerId(), request.nonce(), nonceTtl))
            throw new RunnerProtocolException(RunnerErrorCode.RUNNER_REPLAY_DETECTED,
                    "Runner nonce was already used");
        return request.runnerId();
    }

    private static RunnerProtocolException invalid()
    {
        return new RunnerProtocolException(RunnerErrorCode.RUNNER_AUTH_INVALID,
                "Runner request authentication failed");
    }
}
