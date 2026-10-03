package com.ruoyi.train.core.runner.config;

import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gputrain.runner-security")
public class RunnerSecurityProperties
{
    private String masterKey;
    private String enrollmentPepper;
    private String allowedClockSkew = "2m";
    private String nonceTtl = "5m";
    private boolean requireHttps = true;
    private boolean allowLoopbackHttp;

    public String getMasterKey()
    {
        return masterKey;
    }

    public byte[] getMasterKeyBytes()
    {
        if (masterKey == null || masterKey.isBlank())
            throw new IllegalStateException("gputrain.runner-security.master-key must be configured");
        final byte[] decoded;
        try
        {
            decoded = Base64.getDecoder().decode(masterKey);
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalStateException("Runner master key must be Base64 encoded", e);
        }
        if (decoded.length != 32)
            throw new IllegalStateException("Runner master key must decode to exactly 32 bytes");
        return decoded;
    }

    public String getEnrollmentPepper()
    {
        if (enrollmentPepper == null || enrollmentPepper.isBlank())
            throw new IllegalStateException("gputrain.runner-security.enrollment-pepper must be configured");
        return enrollmentPepper;
    }

    public void setMasterKey(String masterKey) { this.masterKey = masterKey; }
    public void setEnrollmentPepper(String enrollmentPepper) { this.enrollmentPepper = enrollmentPepper; }
    public String getAllowedClockSkew() { return allowedClockSkew; }
    public void setAllowedClockSkew(String allowedClockSkew) { this.allowedClockSkew = allowedClockSkew; }
    public String getNonceTtl() { return nonceTtl; }
    public void setNonceTtl(String nonceTtl) { this.nonceTtl = nonceTtl; }
    public boolean isRequireHttps() { return requireHttps; }
    public void setRequireHttps(boolean requireHttps) { this.requireHttps = requireHttps; }
    public boolean isAllowLoopbackHttp() { return allowLoopbackHttp; }
    public void setAllowLoopbackHttp(boolean allowLoopbackHttp) { this.allowLoopbackHttp = allowLoopbackHttp; }
}
