package com.ruoyi.train.core.runner.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RunnerCredentialCipherTest
{
    @Test
    void cipherRoundTripsWithoutEmbeddingPlaintext()
    {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        RunnerCredentialCipher cipher = new RunnerCredentialCipher(key,
                new SecureRandom(new byte[] { 1, 2, 3 }));
        String encrypted = cipher.encrypt("runner-secret".getBytes(StandardCharsets.UTF_8));
        assertFalse(encrypted.contains("runner-secret"));
        assertArrayEquals("runner-secret".getBytes(StandardCharsets.UTF_8), cipher.decrypt(encrypted));
    }

    @Test
    void rejectsTamperedCiphertext()
    {
        byte[] key = new byte[32];
        RunnerCredentialCipher cipher = new RunnerCredentialCipher(key, new SecureRandom());
        String encrypted = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8));
        String[] fields = encrypted.split("\\.");
        byte[] ciphertext = Base64.getUrlDecoder().decode(fields[2]);
        ciphertext[0] ^= 1;
        fields[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(String.join(".", fields)));
    }
}
