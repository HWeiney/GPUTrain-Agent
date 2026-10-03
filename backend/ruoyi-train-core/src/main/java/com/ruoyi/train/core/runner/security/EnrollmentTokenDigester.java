package com.ruoyi.train.core.runner.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;

@Component
public final class EnrollmentTokenDigester
{
    private final byte[] pepper;

    @Autowired
    public EnrollmentTokenDigester(RunnerSecurityProperties properties)
    {
        this(properties.getEnrollmentPepper().getBytes(StandardCharsets.UTF_8));
    }

    public EnrollmentTokenDigester(byte[] pepper)
    {
        if (pepper == null || pepper.length == 0)
            throw new IllegalArgumentException("Enrollment token pepper must not be blank");
        this.pepper = pepper.clone();
    }

    public String digest(String token)
    {
        Objects.requireNonNull(token, "token");
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        }
        catch (GeneralSecurityException e)
        {
            throw new IllegalStateException("Unable to digest enrollment token", e);
        }
    }

    public boolean matches(String token, String expectedDigest)
    {
        if (token == null || expectedDigest == null)
            return false;
        try
        {
            byte[] expected = Base64.getUrlDecoder().decode(expectedDigest);
            byte[] actual = Base64.getUrlDecoder().decode(digest(token));
            return MessageDigest.isEqual(actual, expected);
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }
    }
}
