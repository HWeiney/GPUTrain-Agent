package com.ruoyi.train.core.server.ssh;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerSecretCipherTest
{
    @Test void randomizedAuthenticatedEncryptionAndWrongKeyRejection()
    {
        var cipher = new ServerSecretCipher(new byte[32]);
        String first = cipher.encrypt("password-秘密");
        String second = cipher.encrypt("password-秘密");
        assertNotEquals(first, second);
        assertTrue(first.startsWith("ssh-v1."));
        assertEquals(12, Base64.getUrlDecoder().decode(first.split("\\.")[1]).length);
        assertEquals("password-秘密", cipher.decrypt(first));
        assertEquals("password-秘密", cipher.decrypt(second));
        byte[] otherKey = new byte[32];
        Arrays.fill(otherKey, (byte) 42);
        assertThrows(SshGateway.SshException.class, () -> new ServerSecretCipher(otherKey).decrypt(first));
    }

    @Test void tamperedMalformedAndUnknownVersionFailWithoutEchoingSecrets()
    {
        var cipher = new ServerSecretCipher(new byte[32]);
        String encrypted = cipher.encrypt("very-secret");
        String[] pieces = encrypted.split("\\.");
        byte[] data = Base64.getUrlDecoder().decode(pieces[2]);
        data[0] ^= 1;
        for (String value : new String[] {"very-secret", "v2.a.b", "ssh-v1.a.b",
            pieces[0] + "." + pieces[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(data)})
        {
            var error = assertThrows(SshGateway.SshException.class, () -> cipher.decrypt(value));
            assertEquals(SshGateway.ErrorCode.CREDENTIAL_INVALID, error.code());
            assertFalse(error.toString().contains(value));
            assertNull(error.getCause());
        }
    }

    @Test void requires32ByteMasterKeyAndDefensivelyCopiesIt()
    {
        assertThrows(IllegalArgumentException.class, () -> new ServerSecretCipher(new byte[16]));
        byte[] key = new byte[32];
        var cipher = new ServerSecretCipher(key);
        String encrypted = cipher.encrypt("secret");
        key[0] = 1;
        assertEquals("secret", cipher.decrypt(encrypted));
    }

    @Test void environmentConfigurationAndTransportLoggingAreSafe() throws Exception
    {
        String application = java.nio.file.Files.readString(java.nio.file.Path.of("../ruoyi-admin/src/main/resources/application.yml"));
        String logging = java.nio.file.Files.readString(java.nio.file.Path.of("../ruoyi-admin/src/main/resources/logback.xml"));
        String example = java.nio.file.Files.readString(java.nio.file.Path.of("../../.env.example"));
        assertTrue(application.contains("master-key: ${GPUTRAIN_SSH_MASTER_KEY:}"));
        assertTrue(application.contains("org.apache.sshd: \"OFF\""));
        assertTrue(logging.contains("<logger name=\"org.apache.sshd\" level=\"OFF\""));
        assertTrue(example.contains("GPUTRAIN_SSH_MASTER_KEY=\n"));
    }

    @Test void absentEnvironmentKeyOnlyDisablesSshCredentialOperations()
    {
        var cipher = new ServerSecretCipher("");
        assertEquals(SshGateway.ErrorCode.MASTER_KEY_NOT_CONFIGURED,
            assertThrows(SshGateway.SshException.class, () -> cipher.encrypt("secret")).code());
    }
}
