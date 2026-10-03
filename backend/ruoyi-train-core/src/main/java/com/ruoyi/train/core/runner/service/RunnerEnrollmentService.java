package com.ruoyi.train.core.runner.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.train.core.runner.domain.RunnerEnrollmentRecord;
import com.ruoyi.train.core.runner.domain.RunnerRecord;
import com.ruoyi.train.core.runner.mapper.RunnerEnrollmentMapper;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;
import com.ruoyi.train.core.runner.security.EnrollmentTokenDigester;
import com.ruoyi.train.core.runner.security.RunnerCredentialCipher;
import com.ruoyi.train.runner.protocol.RunnerCredentials;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;
import com.ruoyi.train.runner.protocol.RunnerRegistration;

@Service
public class RunnerEnrollmentService
{
    private final RunnerEnrollmentMapper enrollmentMapper;
    private final RunnerMapper runnerMapper;
    private final EnrollmentTokenDigester tokenDigester;
    private final RunnerCredentialCipher credentialCipher;
    private final SecureRandom random;

    @Autowired
    public RunnerEnrollmentService(RunnerEnrollmentMapper enrollmentMapper, RunnerMapper runnerMapper,
            EnrollmentTokenDigester tokenDigester, RunnerCredentialCipher credentialCipher)
    {
        this(enrollmentMapper, runnerMapper, tokenDigester, credentialCipher, new SecureRandom());
    }

    public RunnerEnrollmentService(RunnerEnrollmentMapper enrollmentMapper, RunnerMapper runnerMapper,
            EnrollmentTokenDigester tokenDigester, RunnerCredentialCipher credentialCipher, SecureRandom random)
    {
        this.enrollmentMapper = Objects.requireNonNull(enrollmentMapper, "enrollmentMapper");
        this.runnerMapper = Objects.requireNonNull(runnerMapper, "runnerMapper");
        this.tokenDigester = Objects.requireNonNull(tokenDigester, "tokenDigester");
        this.credentialCipher = Objects.requireNonNull(credentialCipher, "credentialCipher");
        this.random = Objects.requireNonNull(random, "random");
    }

    @Transactional
    public EnrollmentGrant createEnrollment(long ownerUserId, String runnerName, String linuxUsername,
            String allowedRoot, Instant expiresAt, String idempotencyKey)
    {
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128)
            throw new IllegalArgumentException("Enrollment idempotency key must contain 1 to 128 characters");
        RunnerEnrollmentRecord existing = enrollmentMapper.findByIdempotencyKey(idempotencyKey);
        if (existing != null)
            return existingGrant(existing, ownerUserId, runnerName, linuxUsername, allowedRoot, expiresAt);
        if (!expiresAt.isAfter(Instant.now()))
            throw new IllegalArgumentException("Enrollment expiry must be in the future");
        String plaintextToken = randomToken();
        String digest = tokenDigester.digest(plaintextToken);
        String tokenCiphertext = credentialCipher.encrypt(plaintextToken.getBytes(StandardCharsets.UTF_8));
        Instant createdAt = Instant.now();
        RunnerEnrollmentRecord record = new RunnerEnrollmentRecord(null, ownerUserId, runnerName,
                linuxUsername, allowedRoot, digest, tokenCiphertext, idempotencyKey, expiresAt, null, createdAt);
        try
        {
            if (enrollmentMapper.insert(record) != 1)
                throw new IllegalStateException("Unable to create Runner enrollment");
        }
        catch (DuplicateKeyException duplicate)
        {
            RunnerEnrollmentRecord winner = enrollmentMapper.findByIdempotencyKey(idempotencyKey);
            if (winner == null)
                throw duplicate;
            return existingGrant(winner, ownerUserId, runnerName, linuxUsername, allowedRoot, expiresAt);
        }
        RunnerEnrollmentRecord inserted = enrollmentMapper.findByIdempotencyKey(idempotencyKey);
        if (inserted == null || inserted.enrollmentId() == null)
            throw new IllegalStateException("Created Runner enrollment could not be retrieved");
        return existingGrant(inserted, ownerUserId, runnerName, linuxUsername, allowedRoot, expiresAt);
    }

    private EnrollmentGrant existingGrant(RunnerEnrollmentRecord record, long ownerUserId,
            String runnerName, String linuxUsername, String allowedRoot, Instant expiresAt)
    {
        if (record.ownerUserId() != ownerUserId || !record.runnerName().equals(runnerName)
                || !record.linuxUsername().equals(linuxUsername)
                || !record.allowedRoot().equals(allowedRoot) || !record.expiresAt().equals(expiresAt))
            throw protocolError(RunnerErrorCode.ENROLLMENT_INVALID,
                    "Enrollment idempotency key is already bound to different enrollment fields");
        if (record.consumedAt() != null || record.tokenCiphertext() == null)
            throw protocolError(RunnerErrorCode.ENROLLMENT_ALREADY_USED,
                    "Enrollment token has already been used");
        String token = new String(credentialCipher.decrypt(record.tokenCiphertext()), StandardCharsets.UTF_8);
        if (!tokenDigester.matches(token, record.tokenDigest()))
            throw new IllegalStateException("Stored enrollment token ciphertext does not match its digest");
        return new EnrollmentGrant(record.enrollmentId(), token, runnerName, linuxUsername,
                allowedRoot, expiresAt);
    }

    @Transactional
    public RunnerCredentials register(RunnerRegistration request, Instant now)
    {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        RunnerEnrollmentRecord enrollment = enrollmentMapper.findById(request.enrollmentId());
        if (enrollment == null || !tokenDigester.matches(request.enrollmentToken(), enrollment.tokenDigest()))
            throw protocolError(RunnerErrorCode.ENROLLMENT_INVALID, "Enrollment token is invalid");
        if (enrollment.consumedAt() != null)
            throw protocolError(RunnerErrorCode.ENROLLMENT_ALREADY_USED, "Enrollment token has already been used");
        if (!enrollment.expiresAt().isAfter(now))
            throw protocolError(RunnerErrorCode.ENROLLMENT_INVALID, "Enrollment token has expired");
        if (!enrollment.runnerName().equals(request.runnerName())
                || !enrollment.linuxUsername().equals(request.linuxUsername()))
            throw protocolError(RunnerErrorCode.ENROLLMENT_INVALID, "Enrollment does not match Runner identity");

        int consumed = enrollmentMapper.consume(enrollment.enrollmentId(), enrollment.tokenDigest(), now);
        if (consumed != 1)
        {
            RunnerEnrollmentRecord current = enrollmentMapper.findById(enrollment.enrollmentId());
            if (current != null && current.consumedAt() != null)
                throw protocolError(RunnerErrorCode.ENROLLMENT_ALREADY_USED, "Enrollment token has already been used");
            throw protocolError(RunnerErrorCode.ENROLLMENT_INVALID, "Enrollment token is invalid or expired");
        }

        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        String ciphertext = credentialCipher.encrypt(secret.getBytes(StandardCharsets.UTF_8));
        RunnerRecord runner = new RunnerRecord(null, enrollment.runnerName(), enrollment.linuxUsername(),
                enrollment.allowedRoot(), ciphertext, "ACTIVE", now, now, now);
        if (runnerMapper.insert(runner) != 1)
            throw new IllegalStateException("Unable to create Runner");
        RunnerRecord created = runnerMapper.findByName(enrollment.runnerName());
        if (created == null || created.runnerId() == null)
            throw new IllegalStateException("Created Runner could not be retrieved");
        return new RunnerCredentials(created.runnerId().toString(), created.runnerName(), secret);
    }

    private String randomToken()
    {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static RunnerProtocolException protocolError(RunnerErrorCode code, String message)
    {
        return new RunnerProtocolException(code, message);
    }
}
