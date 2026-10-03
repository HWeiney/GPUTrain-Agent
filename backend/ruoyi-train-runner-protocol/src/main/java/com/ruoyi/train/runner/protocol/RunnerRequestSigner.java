package com.ruoyi.train.runner.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RunnerRequestSigner
{
    private RunnerRequestSigner() { }

    public static String canonical(String runnerId, long timestamp, String nonce,
            String method, String path, byte[] body)
    {
        Objects.requireNonNull(runnerId, "runnerId");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(body, "body");
        return String.join("\n", runnerId, Long.toString(timestamp), nonce,
            method.toUpperCase(Locale.ROOT), path, HexFormat.of().formatHex(sha256(body)));
    }

    public static String sign(byte[] secret, String canonical)
    {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(canonical, "canonical");
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.GeneralSecurityException e)
        {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] sha256(byte[] body)
    {
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(body);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
