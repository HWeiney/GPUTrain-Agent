package com.ruoyi.train.core.runner.security;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;

@Component
public final class RunnerCredentialCipher
{
    private static final String VERSION = "v1";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private final byte[] key;
    private final SecureRandom random;

    @Autowired
    public RunnerCredentialCipher(RunnerSecurityProperties properties)
    {
        this(properties.getMasterKeyBytes(), new SecureRandom());
    }

    public RunnerCredentialCipher(byte[] key)
    {
        this(key, new SecureRandom());
    }

    public RunnerCredentialCipher(byte[] key, SecureRandom random)
    {
        if (key == null || key.length != 32)
            throw new IllegalArgumentException("Runner master key must contain exactly 32 bytes");
        this.key = key.clone();
        this.random = Objects.requireNonNull(random, "random");
    }

    public String encrypt(byte[] plaintext)
    {
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try
        {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return VERSION + "." + encoder.encodeToString(iv) + "." + encoder.encodeToString(ciphertext);
        }
        catch (GeneralSecurityException e)
        {
            throw new IllegalStateException("Unable to encrypt Runner credential", e);
        }
    }

    public byte[] decrypt(String encoded)
    {
        try
        {
            String[] fields = encoded.split("\\.", -1);
            if (fields.length != 3 || !VERSION.equals(fields[0]))
                throw new IllegalArgumentException("Unsupported Runner credential format");
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(fields[1]);
            byte[] ciphertext = decoder.decode(fields[2]);
            if (iv.length != IV_LENGTH || ciphertext.length < TAG_LENGTH_BITS / 8)
                throw new IllegalArgumentException("Invalid Runner credential format");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        }
        catch (GeneralSecurityException | NullPointerException | IllegalArgumentException e)
        {
            throw new IllegalArgumentException("Unable to decrypt Runner credential", e);
        }
    }
}
