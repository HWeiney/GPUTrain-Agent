package com.ruoyi.train.core.runner.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.ruoyi.train.core.runner.config.RunnerSecurityProperties;
import com.ruoyi.train.runner.protocol.RunnerErrorCode;
import com.ruoyi.train.runner.protocol.RunnerProtocolException;

@Component
public final class RunnerTransportSecurityGuard
{
    private final RunnerSecurityProperties properties;

    public RunnerTransportSecurityGuard(RunnerSecurityProperties properties)
    {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public void assertAllowed(boolean secure, String remoteAddress)
    {
        if (secure)
            return;
        if (!properties.isRequireHttps() && properties.isAllowLoopbackHttp() && isLoopback(remoteAddress))
            return;
        throw new RunnerProtocolException(RunnerErrorCode.RUNNER_HTTPS_REQUIRED,
                "Runner credential endpoints require HTTPS");
    }

    private static boolean isLoopback(String address)
    {
        if (address == null || address.isBlank())
            return false;
        try
        {
            return InetAddress.getByName(address).isLoopbackAddress();
        }
        catch (UnknownHostException e)
        {
            return false;
        }
    }
}
