package com.ruoyi.train.core.server.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.exception.ServiceException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.*;
import java.util.*;
import com.ruoyi.train.core.server.domain.*;
import com.ruoyi.train.core.server.mapper.*;
import com.ruoyi.train.core.server.ssh.*;

@Service
public class ServerInventoryService {
    public record CreateServer(String name, String host, int port, String hostKeyFingerprint, boolean confirmDuplicateFingerprint) {}
    public record UpdateServer(String name, Boolean enabled, String hostKeyFingerprint, boolean confirmHostKeyChange, boolean confirmDuplicateFingerprint) {}
    public record CreateAccount(Long ownerUserId, String linuxUsername, String authType, String credential, String passphrase, String allowedRoot) {
        @Override public String toString() { return "CreateAccount[redacted]"; }
    }
    public record RotateCredentials(boolean rotate, String credential, String passphrase) {
        @Override public String toString() { return "RotateCredentials[redacted]"; }
    }
    public record ServerView(Long serverId, String name, String host, int port, String hostKeyFingerprint, boolean enabled, Instant lastProbeAt, Instant lastSuccessAt, String status, Map<String, Boolean> capabilities) {}
    public record ServerAccountView(Long accountId, Long serverId, Long ownerUserId, String linuxUsername, String authType, boolean hasCredential, String allowedRoot) {}
    public record ServerEntry(ServerView server, List<ServerAccountView> accounts) {}
    public record ServerDetail(ServerView server, List<ServerAccountView> accounts, ServerSnapshotRecord snapshot) {}
    private final ServerMapper servers;
    private final ServerAccountMapper accounts;
    private final ServerInventoryMapper inventory;
    private final ServerSnapshotMapper snapshots;
    private final SshGateway gateway;
    private final ServerSecretCipher cipher;
    private final ServerProbeService probes;
    private final ServerDeviceQueryService deviceQueries;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public ServerInventoryService(ServerMapper servers, ServerAccountMapper accounts, ServerInventoryMapper inventory,
        ServerSnapshotMapper snapshots, SshGateway gateway, ServerSecretCipher cipher,
        ServerProbeService probes, ServerDeviceQueryService deviceQueries) {
        this(servers, accounts, inventory, snapshots, gateway, cipher, probes, deviceQueries, Clock.systemUTC());
    }
    public ServerInventoryService(ServerMapper servers, ServerAccountMapper accounts, ServerInventoryMapper inventory,
        ServerSnapshotMapper snapshots, SshGateway gateway, ServerSecretCipher cipher,
        ServerProbeService probes, ServerDeviceQueryService deviceQueries, Clock clock) {
        this.servers=servers; this.accounts=accounts; this.inventory=inventory; this.snapshots=snapshots;
        this.gateway=gateway; this.cipher=cipher; this.probes=probes; this.deviceQueries=deviceQueries; this.clock=clock;
    }
    public List<ServerEntry> list(Long user) {
        requireUser(user);
        return servers.listForOwner(user, SecurityUtils.isAdmin(user)).stream().map(server -> {
            List<ServerAccountRecord> own=accounts.listForOwner(server.serverId(), user);
            return new ServerEntry(view(server, latest(own)), own.stream().map(this::accountView).toList());
        }).toList();
    }
    public ServerDetail detail(Long serverId, Long accountId, Long user) {
        requireUser(user);
        ServerRecord server=requireServer(serverId);
        List<ServerAccountRecord> own=accounts.listForOwner(serverId, user);
        if (!SecurityUtils.isAdmin(user) && own.isEmpty()) throw unavailable();
        ServerAccountRecord selected=accountId == null ? latest(own) : requireOwned(serverId, accountId, user);
        // With multiple accounts, the caller must select one before any snapshot can be exposed.
        ServerSnapshotRecord snapshot=accountId == null ? null : snapshots.findLatest(serverId, accountId);
        return new ServerDetail(view(server, selected), own.stream().map(this::accountView).toList(), snapshot);
    }
    public ServerProbeResult probe(Long serverId, Long accountId, Long user) {
        requireOwned(serverId, accountId, user);
        return probes.probe(serverId, accountId);
    }
    public List<ServerDeviceQueryService.DeviceView> devices(Long serverId, Long accountId, Long user) {
        requireOwned(serverId, accountId, user);
        return deviceQueries.list(serverId, accountId, user);
    }
    public SshGateway.HostKeyPreview preview(String host, int port, Long user) {
        requireAdmin(user);
        return gateway.preview(normalizeHost(host), validPort(port), SshGateway.DEFAULT_CONNECT_TIMEOUT);
    }
    public synchronized ServerView create(CreateServer request, Long user) {
        requireAdmin(user);
        String host=normalizeHost(request.host()); int port=validPort(request.port());
        if (request.name() == null || request.name().isBlank() || request.name().length()>128) throw invalid();
        if (request.hostKeyFingerprint() == null || !request.hostKeyFingerprint().matches("SHA256:[A-Za-z0-9+/]{43}"))
        throw new SshGateway.SshException(SshGateway.ErrorCode.HOST_KEY_UNCONFIRMED);
        if (inventory.findByAddress(host, port) != null) throw new ServiceException("DUPLICATE_SERVER_ADDRESS");
        var observed=gateway.preview(host, port, SshGateway.DEFAULT_CONNECT_TIMEOUT);
        if (!MessageDigest.isEqual(request.hostKeyFingerprint().getBytes(StandardCharsets.US_ASCII), observed.sha256().getBytes(StandardCharsets.US_ASCII)))
        throw new SshGateway.SshException(SshGateway.ErrorCode.HOST_KEY_CHANGED);
        if (!inventory.findByFingerprint(observed.sha256()).isEmpty() && !request.confirmDuplicateFingerprint())
        throw new ServiceException("DUPLICATE_FINGERPRINT_CONFIRMATION_REQUIRED");
        Instant now=clock.instant();
        try { servers.insert(new ServerRecord(null, request.name().trim(), host, port, observed.sha256(), true, now, now)); }
        catch (DuplicateKeyException duplicate) { throw new ServiceException("DUPLICATE_SERVER_ADDRESS"); }
        return view(inventory.findByAddress(host, port), null);
    }
    public List<ServerAccountView> assign(Long serverId, CreateAccount request, Long user) {
        requireAdmin(user); requireServer(serverId);
        if (request.ownerUserId()==null || !inventory.userExists(request.ownerUserId())
            || request.linuxUsername()==null || !request.linuxUsername().matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}[$]?")
            || !Set.of("PASSWORD", "PRIVATE_KEY").contains(Objects.toString(request.authType(), ""))) throw invalid();
        if (request.allowedRoot()==null || !request.allowedRoot().startsWith("/") || request.allowedRoot().length()>1024
            || request.allowedRoot().chars().anyMatch(c -> c<32 || c==127)) throw invalid();
        if (request.credential()==null || request.credential().isEmpty()) throw new SshGateway.SshException(SshGateway.ErrorCode.CREDENTIAL_INVALID);
        if ("PASSWORD".equals(request.authType()) && request.passphrase()!=null && !request.passphrase().isEmpty()) throw invalid();
        String secret=cipher.encrypt(request.credential());
        String pass=request.passphrase()==null || request.passphrase().isEmpty() ? null : cipher.encrypt(request.passphrase());
        requireStorableSecrets(secret, pass);
        Instant now=clock.instant();
        try { accounts.insert(new ServerAccountRecord(null, serverId, request.ownerUserId(), request.linuxUsername(), request.authType(), secret, pass, true, request.allowedRoot(), "{}", true, "UNKNOWN", null, null, null, now, now)); }
        catch (DuplicateKeyException duplicate) { throw new ServiceException("DUPLICATE_SERVER_ACCOUNT"); }
        return accounts.listForOwner(serverId, request.ownerUserId()).stream().map(this::accountView).toList();
    }
    public synchronized ServerView update(Long serverId, UpdateServer request, Long user) {
        requireAdmin(user);
        ServerRecord current=requireServer(serverId);
        String name=request.name()==null ? current.name() : request.name().trim();
        if (name.isBlank() || name.length()>128) throw invalid();
        String fingerprint=request.hostKeyFingerprint()==null ? current.hostKeySha256() : request.hostKeyFingerprint();
        if (!fingerprint.equals(current.hostKeySha256())) {
            if (!request.confirmHostKeyChange() || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}"))
                throw new SshGateway.SshException(SshGateway.ErrorCode.HOST_KEY_UNCONFIRMED);
            var observed=gateway.preview(current.host(), current.port(), SshGateway.DEFAULT_CONNECT_TIMEOUT);
            if (!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII), observed.sha256().getBytes(StandardCharsets.US_ASCII)))
                throw new SshGateway.SshException(SshGateway.ErrorCode.HOST_KEY_CHANGED);
            if (!request.confirmDuplicateFingerprint() && inventory.findByFingerprint(fingerprint).stream().anyMatch(s -> !s.serverId().equals(serverId)))
                throw new ServiceException("DUPLICATE_FINGERPRINT_CONFIRMATION_REQUIRED");
        }
        // As in disable(), no SSH jobs exist until the execution migration adds server_id to train_job.
        // Omitted fields must stay omitted through the eventual SQL update: a concurrent disable can
        // commit while host-key preview is in flight, and a stale pre-preview value must not undo it.
        inventory.updateMetadata(serverId, name, fingerprint, request.enabled(), clock.instant());
        return view(requireServer(serverId), null);
    }
    public ServerAccountView rotate(Long serverId, Long accountId, RotateCredentials request, Long user) {
        ServerAccountRecord account=requireOwned(serverId, accountId, user);
        boolean emptyCredential=request.credential()==null || request.credential().isEmpty();
        boolean emptyPassphrase=request.passphrase()==null || request.passphrase().isEmpty();
        if (emptyCredential && emptyPassphrase) return accountView(account);
        if (!request.rotate()) throw new ServiceException("CREDENTIAL_ROTATION_REQUIRED");
        if (emptyCredential || ("PASSWORD".equals(account.authType()) && !emptyPassphrase)) throw invalid();
        String credential=cipher.encrypt(request.credential());
        String passphrase=emptyPassphrase ? null : cipher.encrypt(request.passphrase());
        requireStorableSecrets(credential, passphrase);
        accounts.updateCredentials(accountId, credential, passphrase, clock.instant());
        return new ServerAccountView(account.accountId(), account.serverId(), account.ownerUserId(), account.linuxUsername(), account.authType(), true, account.allowedRoot());
    }
    public void disable(Long serverId, Long user) {
        requireAdmin(user); requireServer(serverId);
        // Inventory phase: train_job has runner_id only; the set of jobs associated with a server is empty.
        // SSH job migration must add its nonterminal server_id/account_id guard here before enabling execution.
        servers.setEnabled(serverId, false, clock.instant());
    }
    private ServerAccountRecord requireOwned(Long serverId, Long accountId, Long user) {
        requireUser(user);
        ServerAccountRecord account=accounts.findById(accountId);
        if (account == null || !user.equals(account.ownerUserId()) || !Objects.equals(serverId, account.serverId())) throw unavailable();
        return account;
    }
    private ServerRecord requireServer(Long id) {
        ServerRecord server=servers.findById(id); if (server==null) throw unavailable(); return server;
    }
    private ServerAccountView accountView(ServerAccountRecord a) {
        return new ServerAccountView(a.accountId(), a.serverId(), a.ownerUserId(), a.linuxUsername(), a.authType(), a.hasCredential(), a.allowedRoot());
    }
    private ServerAccountRecord latest(List<ServerAccountRecord> own) {
        return own.stream().max(Comparator.comparing(a -> a.lastProbeAt()==null ? Instant.MIN : a.lastProbeAt())).orElse(null);
    }
    private ServerView view(ServerRecord server, ServerAccountRecord account) {
        String status=account==null ? "UNKNOWN" : account.status();
        if (!server.enabled() || (account!=null && !account.enabled())) status="OFFLINE";
        else if (Set.of("ONLINE", "DEGRADED").contains(status) && (account.lastSuccessAt()==null
            || !Objects.equals(account.lastProbeAt(), account.lastSuccessAt())
            || account.lastSuccessAt().isBefore(clock.instant().minusSeconds(30))
            || account.lastSuccessAt().isAfter(clock.instant().plusSeconds(60)))) status="OFFLINE";
        Map<String, Boolean> capabilities=new LinkedHashMap<>();
        for (String key:List.of("ssh", "gpu", "systemdUser", "persistentUserManager")) capabilities.put(key, false);
        if (account!=null && Set.of("ONLINE", "DEGRADED").contains(status)) {
            ServerSnapshotRecord snapshot=snapshots.findLatest(server.serverId(), account.accountId());
            if (snapshot!=null && Objects.equals(snapshot.sampledAt(), account.lastSuccessAt())) {
                try { var node=json.readTree(snapshot.capabilitiesJson()); for (String key:capabilities.keySet()) capabilities.put(key, node.path(key).asBoolean(false)); }
                catch (Exception ignored) { /* Unknown capability evidence remains false. */ }
            }
        }
        return new ServerView(server.serverId(), server.name(), server.host(), server.port(), server.hostKeySha256(), server.enabled(),
            account==null ? null:account.lastProbeAt(), account==null ? null:account.lastSuccessAt(), status, Map.copyOf(capabilities));
    }
    static String normalizeHost(String value) {
        if (value==null || value.isBlank() || value.length()>255 || !value.equals(value.trim())) throw invalid();
        String host=value.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host=host.substring(0, host.length()-1);
        if (host.contains(":")) {
            if (!host.matches("[0-9a-f:]+")) throw invalid();
            try { return InetAddress.getByName(host).getHostAddress(); } catch (UnknownHostException bad) { throw invalid(); }
        }
        if (!host.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")) throw invalid();
        if (host.matches("[0-9.]+")) {
            String[] parts=host.split("\\."); if (parts.length!=4) throw invalid();
            for (String part:parts) { if (part.length()>3 || Integer.parseInt(part)>255 || (part.length()>1 && part.startsWith("0"))) throw invalid(); }
        }
        return host;
    }
    private static void requireStorableSecrets(String credential, String passphrase) {
        // AES-GCM envelopes use ASCII; database limits apply to the encoded envelope, not plaintext.
        if (credential.length()>65535 || (passphrase!=null && passphrase.length()>2048))
        throw new SshGateway.SshException(SshGateway.ErrorCode.CREDENTIAL_INVALID);
    }
    static int validPort(int port) { if (port<1 || port>65535) throw invalid(); return port; }
    private static void requireUser(Long user) { if (user==null || user<=0) throw new AccessDeniedException("Authentication required"); }
    private static void requireAdmin(Long user) { requireUser(user); if (!SecurityUtils.isAdmin(user)) throw new AccessDeniedException("Administrator required"); }
    private static ServiceException invalid() { return new ServiceException("INVALID_SERVER_REQUEST"); }
    private static ServiceException unavailable() { return new ServiceException("SERVER_ACCOUNT_UNAVAILABLE"); }
}
