package com.ruoyi.train.core.server.ssh;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Base64;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;

/** OpenSSH SHA-256 fingerprint of the SSH wire-format public key, never an X.509 digest. */
public final class HostKeyFingerprint
{
    private HostKeyFingerprint() { }

    public static SshGateway.HostKeyPreview of(PublicKey key)
    {
        return new SshGateway.HostKeyPreview(KeyUtils.getKeyType(key), KeyUtils.getFingerPrint(BuiltinDigests.sha256, key));
    }

    public static boolean matches(String confirmed, PublicKey actual)
    {
        byte[] expected = decode(confirmed);
        byte[] observed = decode(of(actual).sha256());
        return expected != null && observed != null && MessageDigest.isEqual(expected, observed);
    }

    public static boolean isValid(String fingerprint) { return decode(fingerprint) != null; }

    private static byte[] decode(String fingerprint)
    {
        if (fingerprint == null || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) return null;
        try
        {
            byte[] digest = Base64.getDecoder().decode(fingerprint.substring(7));
            return digest.length == 32 ? digest : null;
        }
        catch (IllegalArgumentException ignored) { return null; }
    }
}
