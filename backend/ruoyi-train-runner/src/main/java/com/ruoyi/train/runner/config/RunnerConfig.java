package com.ruoyi.train.runner.config;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public record RunnerConfig(URI controlPlaneUrl, String runnerId, Path credentialFile,
        Path allowedRoot, Set<Path> executableAllowlist, Set<String> environmentAllowlist,
        Duration heartbeatInterval, Duration pollingTimeout,
        String nvidiaSmiPath, Duration discoveryInterval)
{
    public RunnerConfig
    {
        validateUrl(controlPlaneUrl);
        if (runnerId == null || runnerId.isBlank()) throw new IllegalArgumentException("runnerId is required");
        Objects.requireNonNull(credentialFile, "credentialFile");
        allowedRoot = real(allowedRoot);
        if (!Files.isDirectory(allowedRoot)) throw new IllegalArgumentException("allowedRoot must be a directory");
        executableAllowlist = executableAllowlist.stream().map(RunnerConfig::real).collect(Collectors.toUnmodifiableSet());
        if (executableAllowlist.isEmpty() || executableAllowlist.stream().anyMatch(p -> !Files.isRegularFile(p) || !Files.isExecutable(p)))
            throw new IllegalArgumentException("Executable allowlist requires executable files");
        environmentAllowlist = Set.copyOf(environmentAllowlist);
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative() || pollingTimeout.isNegative()
                || pollingTimeout.compareTo(Duration.ofSeconds(20)) > 0)
            throw new IllegalArgumentException("Invalid heartbeat or polling interval");
        if (nvidiaSmiPath == null || nvidiaSmiPath.isBlank())
            throw new IllegalArgumentException("nvidiaSmiPath is required");
        if (discoveryInterval == null || discoveryInterval.isZero() || discoveryInterval.isNegative()
                || discoveryInterval.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("Invalid discovery interval");
    }

    public RunnerConfig(URI controlPlaneUrl, String runnerId, Path credentialFile,
            Path allowedRoot, Set<Path> executableAllowlist, Set<String> environmentAllowlist,
            Duration heartbeatInterval, Duration pollingTimeout)
    {
        this(controlPlaneUrl, runnerId, credentialFile, allowedRoot, executableAllowlist,
                environmentAllowlist, heartbeatInterval, pollingTimeout,
                "nvidia-smi", Duration.ofSeconds(15));
    }

    public static RunnerConfig load(Path path) throws IOException
    {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { p.load(reader); }
        return from(p);
    }

    public static RunnerConfig from(Properties p)
    {
        return new RunnerConfig(URI.create(required(p, "controlPlaneUrl")), required(p, "runnerId"),
            Path.of(required(p, "credentialFile")).toAbsolutePath().normalize(), Path.of(required(p, "allowedRoot")),
            split(required(p, "executableAllowlist")).stream().map(Path::of).collect(Collectors.toSet()),
            split(p.getProperty("environmentAllowlist", "")),
            Duration.ofSeconds(Long.parseLong(p.getProperty("heartbeatIntervalSeconds", "10"))),
            Duration.ofSeconds(Long.parseLong(p.getProperty("pollingTimeoutSeconds", "20"))),
            p.getProperty("nvidiaSmiPath", "nvidia-smi"),
            Duration.ofSeconds(Long.parseLong(p.getProperty("discoveryIntervalSeconds", "15"))));
    }

    public byte[] readSecret() throws IOException
    {
        Path file = credentialFile.toRealPath();
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Credential must be a regular file");
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
        {
            var permissions = Files.getPosixFilePermissions(file);
            if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
                throw new IllegalArgumentException("Credential file must be private to its owner");
        }
        byte[] secret;
        try { secret = Base64.getUrlDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).strip()); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid credential encoding"); }
        if (secret.length != 32) throw new IllegalArgumentException("Credential must contain a 256-bit secret");
        return secret;
    }

    public static void validateUrl(URI uri)
    {
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host);
        if (host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                || !("https".equalsIgnoreCase(uri.getScheme()) || ("http".equalsIgnoreCase(uri.getScheme()) && loopback)))
            throw new IllegalArgumentException("Control plane requires HTTPS or a loopback HTTP origin");
    }

    private static String required(Properties p, String name)
    {
        String value = p.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.strip();
    }
    private static Set<String> split(String values)
    { return Arrays.stream(values.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet()); }
    private static Path real(Path path)
    {
        try { return path.toRealPath(); }
        catch (IOException failure) { throw new IllegalArgumentException("Configured path does not exist", failure); }
    }
}
