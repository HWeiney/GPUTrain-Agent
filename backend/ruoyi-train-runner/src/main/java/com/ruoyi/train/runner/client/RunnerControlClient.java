package com.ruoyi.train.runner.client;

import java.io.IOException;
import java.net.http.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ruoyi.train.runner.config.RunnerConfig;
import com.ruoyi.train.runner.protocol.*;

public final class RunnerControlClient
{
    private final RunnerConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final SecureRandom random = new SecureRandom();

    public RunnerControlClient(RunnerConfig config) { this.config = Objects.requireNonNull(config); }

    public RunnerCredentials register(RunnerRegistration registration) throws IOException, InterruptedException
    { return json.readValue(send("POST", "/runner-api/v1/register", registration, false), RunnerCredentials.class); }

    public void heartbeat(RunnerHeartbeat heartbeat) throws IOException, InterruptedException
    { send("POST", "/runner-api/v1/heartbeat", heartbeat, true); }

    public Optional<RunnerCommandLease> poll() throws IOException, InterruptedException
    {
        byte[] response = send("GET", "/runner-api/v1/commands/poll?timeoutSeconds=" + config.pollingTimeout().toSeconds(), null, true);
        return response.length == 0 ? Optional.empty() : Optional.of(json.readValue(response, RunnerCommandLease.class));
    }

    public void report(UUID id, RunnerCommandReport report) throws IOException, InterruptedException
    { send("POST", "/runner-api/v1/commands/" + id + "/reports", report, true); }

    public void reconcile(List<RunnerProcessSnapshot> snapshots) throws IOException, InterruptedException
    { send("POST", "/runner-api/v1/processes/reconcile", snapshots, true); }

    private byte[] send(String method, String path, Object value, boolean signed) throws IOException, InterruptedException
    {
        byte[] body = value == null ? new byte[0] : json.writeValueAsBytes(value);
        var uri = config.controlPlaneUrl().resolve(path);
        var builder = HttpRequest.newBuilder(uri).timeout(config.pollingTimeout().plusSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (signed)
        {
            byte[] nonceBytes = new byte[16]; random.nextBytes(nonceBytes);
            String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
            long timestamp = Instant.now().getEpochSecond();
            byte[] secret = config.readSecret();
            try
            {
                builder.header("X-Runner-Id", config.runnerId()).header("X-Runner-Timestamp", Long.toString(timestamp))
                        .header("X-Runner-Nonce", nonce).header("X-Runner-Signature", RunnerRequestSigner.sign(secret,
                            RunnerRequestSigner.canonical(config.runnerId(), timestamp, nonce, method, uri.getRawPath(), body)));
            }
            finally { Arrays.fill(secret, (byte) 0); }
        }
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            // Server messages and response bodies may contain caller-supplied values; never echo them.
            RunnerErrorCode code = null;
            try
            {
                JsonNode error = json.readTree(response.body());
                if (error != null) code = RunnerErrorCode.valueOf(error.path("errorCode").asText());
            }
            catch (IOException | IllegalArgumentException ignored) { }
            if (code != null) throw new RunnerProtocolException(code, "Control plane rejected request");
            throw new HttpFailure(response.statusCode());
        }
        return response.body();
    }

    public static final class HttpFailure extends IOException
    {
        private final int status;
        public HttpFailure(int status) { super("Control plane HTTP status " + status); this.status = status; }
        public boolean retryable() { return status == 408 || status == 429 || status >= 500; }
    }
}
