package com.ruoyi.train.core.server.ssh;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static com.ruoyi.train.core.server.ssh.SshGateway.ErrorCode.*;
import com.ruoyi.train.core.server.ssh.SshGateway.SshException;

@Component
public final class ServerSecretCipher
{
    private static final String VERSION = "ssh-v1";
    private static final int MAX_SECRET_BYTES = 65536;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ServerSecretCipher(@Value("${gputrain.ssh.master-key:}") String base64Key)
    {
        if (base64Key == null || base64Key.isBlank()) { key = null; return; }
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(base64Key); }
        catch (IllegalArgumentException ignored) { throw new IllegalArgumentException("Invalid SSH master key configuration"); }
        if (decoded.length != 32) throw new IllegalArgumentException("SSH master key must contain exactly 32 bytes");
        key = decoded;
    }

    public ServerSecretCipher(byte[] key)
    {
        if (key == null || key.length != 32) throw new IllegalArgumentException("SSH master key must contain exactly 32 bytes");
        this.key = key.clone();
    }

    public String encrypt(String plaintext)
    {
        requireKey();
        if (plaintext == null || plaintext.length() > MAX_SECRET_BYTES) throw new SshException(CREDENTIAL_INVALID);
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SECRET_BYTES) { Arrays.fill(bytes, (byte) 0); throw new SshException(CREDENTIAL_INVALID); }
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        try
        {
            Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, iv);
            var encoder = Base64.getUrlEncoder().withoutPadding();
            return VERSION + "." + encoder.encodeToString(iv) + "." + encoder.encodeToString(cipher.doFinal(bytes));
        }
        catch (GeneralSecurityException ignored) { throw new SshException(CREDENTIAL_INVALID); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    public String decrypt(String encoded)
    {
        requireKey();
        byte[] bytes = null;
        try
        {
            if (encoded == null || encoded.length() > MAX_SECRET_BYTES * 2) throw new IllegalArgumentException();
            String[] fields = encoded.split("\\.", -1);
            if (fields.length != 3 || !VERSION.equals(fields[0])) throw new IllegalArgumentException();
            var decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(fields[1]);
            byte[] ciphertext = decoder.decode(fields[2]);
            if (iv.length != 12 || ciphertext.length < 16 || ciphertext.length > MAX_SECRET_BYTES + 16)
                throw new IllegalArgumentException();
            bytes = createCipher(Cipher.DECRYPT_MODE, iv).doFinal(ciphertext);
            return new String(bytes, StandardCharsets.UTF_8);
        }
        catch (GeneralSecurityException | IllegalArgumentException ignored) { throw new SshException(CREDENTIAL_INVALID); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    private Cipher createCipher(int mode, byte[] iv) throws GeneralSecurityException
    {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(VERSION.getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }

    private void requireKey() { if (key == null) throw new SshException(MASTER_KEY_NOT_CONFIGURED); }
}
