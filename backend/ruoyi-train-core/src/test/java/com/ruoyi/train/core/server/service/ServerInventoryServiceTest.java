package com.ruoyi.train.core.server.service;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.server.domain.*;
import com.ruoyi.train.core.server.mapper.*;
import com.ruoyi.train.core.server.ssh.*;
import com.ruoyi.train.core.server.service.ServerInventoryService.*;

class ServerInventoryServiceTest
{
    static final Instant NOW = Instant.parse("2026-10-09T01:00:00Z");
    static final String FP = "SHA256:" + "A".repeat(43);
    final List<ServerRecord> rows = new ArrayList<>();
    final List<ServerAccountRecord> identities = new ArrayList<>();
    final ServerSecretCipher cipher = new ServerSecretCipher(new byte[32]);
    String previewFingerprint = FP;
    String previewHost;
    Runnable previewHook = () -> {};
    int secretReads, credentialWrites, snapshotReads, probes;
    ServerAccountRecord written;
    ServerAccountRecord tested;
    int connectionTests;
    final ServerMapper servers = proxy(ServerMapper.class, (m, a) -> switch(m) {
        case "listForOwner" -> rows.stream().filter(s -> (boolean)a[1] || identities.stream().anyMatch(i -> i.serverId().equals(s.serverId()) && i.ownerUserId().equals(a[0]))).toList();
        case "findById" -> rows.stream().filter(s -> s.serverId().equals(a[0])).findFirst().orElse(null);
        case "insert" -> { ServerRecord s=(ServerRecord)a[0]; rows.add(new ServerRecord((long) rows.size()+1,s.name(),s.host(),s.port(),s.hostKeySha256(),true,NOW,NOW)); yield 1; }
        case "setEnabled" -> { ServerRecord s=rows.get(0); rows.set(0,new ServerRecord(s.serverId(),s.name(),s.host(),s.port(),s.hostKeySha256(),(boolean)a[1],NOW,NOW)); yield 1; }
        default -> throw new AssertionError(m);
    });
    final ServerAccountMapper accounts = proxy(ServerAccountMapper.class, (m,a) -> switch(m) {
        case "findById" -> identities.stream().filter(i -> i.accountId().equals(a[0])).findFirst().orElse(null);
        case "listForOwner" -> identities.stream().filter(i -> i.serverId().equals(a[0]) && i.ownerUserId().equals(a[1])).toList();
        case "findCredentialsByAuthorizedAccountId" -> { secretReads++; yield identities.get(0); }
        case "insert" -> { written=(ServerAccountRecord)a[0]; identities.add(written); yield 1; }
        case "updateCredentials" -> { credentialWrites++; assertEquals("new-secret",cipher.decrypt((String)a[1])); yield 1; }
        default -> throw new AssertionError(m);
    });
    final ServerInventoryMapper inventory = proxy(ServerInventoryMapper.class, (m,a) -> switch(m) {
        case "findByAddress" -> rows.stream().filter(s -> s.host().equals(a[0]) && s.port()==(int)a[1]).findFirst().orElse(null);
        case "findByFingerprint" -> rows.stream().filter(s -> s.hostKeySha256().equals(a[0])).toList();
        case "userExists" -> a[0].equals(42L);
        case "updateMetadata" -> { var old=rows.get(0); rows.set(0,new ServerRecord(old.serverId(),(String)a[1],old.host(),old.port(),(String)a[2],a[3] == null ? old.enabled() : (boolean)a[3],NOW,NOW)); yield 1; }
        default -> throw new AssertionError(m);
    });
    final ServerSnapshotMapper snapshots = proxy(ServerSnapshotMapper.class, (m,a) -> { snapshotReads++; return null; });
    final SshGateway gateway = new SshGateway() {
        public HostKeyPreview preview(String host,int port,Duration timeout) { previewHost=host; previewHook.run(); return new HostKeyPreview("ssh-ed25519",previewFingerprint); }
        public <T> T withVerifiedSession(ServerRecord s,ServerAccountRecord a,java.util.function.Function<SshSession,T> op) {
            tested=a; connectionTests++;
            try (SshSession session = new SshSession() {
                public CommandResult exec(List<String> command,Duration timeout,int limit) { throw new AssertionError(); }
                public void close() { }
            }) { return op.apply(session); }
        }
    };
    ServerInventoryService service() { return new ServerInventoryService(servers, accounts, inventory, snapshots, gateway, cipher, null, null, Clock.fixed(NOW, ZoneOffset.UTC)); }
    ServerRecord server() { ServerRecord s=new ServerRecord(1L,"GPU","gpu.local",22,FP,true,NOW,NOW); rows.add(s); return s; }
    ServerAccountRecord account(long owner) { var a=new ServerAccountRecord(7L,1L,owner,"alice","PASSWORD","cipher-secret","pass-secret",true,"/work","{}",true,"ONLINE",null,NOW,NOW,NOW,NOW); identities.add(a); return a; }
    CreateServer request(String host, String fp, boolean confirmed) { return new CreateServer("GPU",host,22,fp,confirmed); }

    @Test void onlyOwnAccountsAppearEvenForAdmin() throws Exception {
        server(); account(42); identities.add(new ServerAccountRecord(8L,1L,1L,"admin","PASSWORD",null,null,false,"/work","{}",true,"UNKNOWN",null,null,null,NOW,NOW));
        assertEquals(7L,service().list(42L).get(0).accounts().get(0).accountId());
        assertEquals(8L,service().list(1L).get(0).accounts().get(0).accountId());
        assertTrue(service().list(99L).isEmpty());
        String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(service().list(42L));
        for(String secret:List.of("cipher-secret","pass-secret","credentialCiphertext","passphraseCiphertext","profileJson")) assertFalse(json.contains(secret));
        assertEquals(0,secretReads);
    }
    @Test void adminMetadataDoesNotAuthorizeAnotherAccountSnapshotOrProbe() {
        server(); account(42);
        assertThrows(RuntimeException.class,()->service().detail(1L,7L,1L));
        assertThrows(RuntimeException.class,()->service().probe(1L,7L,1L));
        assertThrows(RuntimeException.class,()->service().devices(1L,7L,1L));
        assertEquals(0,snapshotReads); assertEquals(0,secretReads);
        assertEquals(1L,service().detail(1L,null,1L).server().serverId());
    }
    @Test void createRequiresAdministratorAndRechecksConfirmedHostKey() {
        assertThrows(RuntimeException.class,()->service().create(request("gpu.local",FP,false),42L));
        previewFingerprint="SHA256:"+"B".repeat(43);
        var failure=assertThrows(SshGateway.SshException.class,()->service().create(request("gpu.local",FP,false),1L));
        assertEquals(SshGateway.ErrorCode.HOST_KEY_CHANGED,failure.code()); assertTrue(rows.isEmpty());
    }
    @Test void duplicateAddressesRejectedAndFingerprintReuseRequiresConfirmation() {
        server();
        assertThrows(RuntimeException.class,()->service().create(request("GPU.LOCAL.",FP,true),1L));
        assertThrows(RuntimeException.class,()->service().create(request("second.local",FP,false),1L));
        assertEquals("second.local",service().create(request("second.local",FP,true),1L).host());
    }
    @Test void rejectsBadHostPortAndRelativeRootBeforeCredentialPersistence() {
        for(String host:List.of("https://gpu","a/b","gpu;id","-host"," ")) assertThrows(RuntimeException.class,()->service().preview(host,22,1L));
        assertThrows(RuntimeException.class,()->service().preview("gpu",0,1L));
        assertThrows(RuntimeException.class,()->service().preview("gpu",65536,1L));
        server();
        assertThrows(RuntimeException.class,()->service().assign(1L,new CreateAccount(42L,"alice","PASSWORD","secret",null,"relative"),1L));
        assertNull(written);
    }
    @Test void adminAssignsEncryptedAccountOnlyToExistingUser() {
        server();
        assertThrows(RuntimeException.class,()->service().assign(1L,new CreateAccount(999L,"alice","PASSWORD","secret",null,"/work"),1L));
        service().assign(1L,new CreateAccount(42L,"alice","PASSWORD","secret",null,"/work"),1L);
        assertEquals("secret",cipher.decrypt(written.credentialCiphertext()));
        assertFalse(written.toString().contains("secret"));
    }
    @Test void connectionTestAuthenticatesEphemeralCredentialsWithoutPersistingThem() {
        server();
        var result=service().testConnection(1L,
            new ConnectionTest(null,42L,"alice","PASSWORD","transient-secret",null),1L);
        assertTrue(result.success()); assertNull(result.errorCode());
        assertEquals(1,connectionTests); assertEquals("transient-secret",cipher.decrypt(tested.credentialCiphertext()));
        assertNull(written); assertEquals(0,credentialWrites); assertEquals(0,secretReads);
        assertThrows(RuntimeException.class,()->service().testConnection(1L,
            new ConnectionTest(null,42L,"alice","PASSWORD","transient-secret",null),42L));
    }
    @Test void disabledAndStaleServersAreNotOnline() {
        server(); account(42);
        assertEquals("ONLINE",service().list(42L).get(0).server().status());
        service().disable(1L,1L);
        assertFalse(service().list(42L).get(0).server().enabled());
        assertEquals("OFFLINE",service().list(42L).get(0).server().status());
        assertThrows(RuntimeException.class,()->service().disable(1L,42L));
    }
    @Test void secretChangeRequiresExplicitRotationAndEmptyInputRetainsCiphertext() {
        server(); account(42);
        service().rotate(1L,7L,new RotateCredentials(false,"",""),42L);
        service().rotate(1L,7L,new RotateCredentials(true,null,null),42L);
        assertEquals(0,credentialWrites); assertEquals(0,secretReads);
        assertThrows(RuntimeException.class,()->service().rotate(1L,7L,new RotateCredentials(false,"new-secret",null),42L));
        assertThrows(RuntimeException.class,()->service().rotate(1L,7L,new RotateCredentials(true,"new-secret",null),1L));
        assertThrows(RuntimeException.class,()->service().rotate(2L,7L,new RotateCredentials(true,"new-secret",null),42L));
        service().rotate(1L,7L,new RotateCredentials(true,"new-secret",null),42L);
        assertEquals(1,credentialWrites); assertEquals(0,secretReads);
    }
    @Test void credentialRequestToStringNeverContainsSecret() {
        String secret="NEVER-DISPLAY";
        assertFalse(new CreateAccount(42L,"alice","PRIVATE_KEY",secret,secret,"/work").toString().contains(secret));
        assertFalse(new RotateCredentials(true,secret,secret).toString().contains(secret));
    }
    @Test void fingerprintRecoveryRequiresAdminExplicitConfirmationAndRepreviewOfOriginalAddress() {
        server(); String changed="SHA256:"+"B".repeat(43);
        assertThrows(RuntimeException.class,()->service().update(1L,new UpdateServer(null,true,changed,true,false),42L));
        assertThrows(RuntimeException.class,()->service().update(1L,new UpdateServer(null,true,changed,false,false),1L));
        assertThrows(SshGateway.SshException.class,()->service().update(1L,new UpdateServer(null,true,changed,true,false),1L));
        assertEquals(FP,rows.get(0).hostKeySha256());
        previewFingerprint=changed;
        var view=service().update(1L,new UpdateServer("recovered",true,changed,true,false),1L);
        assertEquals(changed,view.hostKeyFingerprint()); assertEquals("gpu.local",previewHost);
        assertEquals("gpu.local",view.host()); assertEquals(22,view.port());
    }
    @Test void encryptedSecretsMustFitDatabaseColumnsBeforeInsertOrRotation() {
        server(); account(42);
        assertThrows(SshGateway.SshException.class,()->service().assign(1L,new CreateAccount(42L,"alice","PRIVATE_KEY","x".repeat(60000),null,"/work"),1L));
        assertThrows(SshGateway.SshException.class,()->service().assign(1L,new CreateAccount(42L,"alice","PRIVATE_KEY","key","x".repeat(2000),"/work"),1L));
        assertThrows(SshGateway.SshException.class,()->service().rotate(1L,7L,new RotateCredentials(true,"x".repeat(60000),null),42L));
        assertNull(written); assertEquals(0,credentialWrites);
    }
    @Test void metadataUpdatePreservesDisableCommittedWhileFingerprintPreviewIsInFlight() throws Exception {
        server();
        String changed = "SHA256:" + "B".repeat(43);
        previewFingerprint = changed;
        CountDownLatch previewStarted = new CountDownLatch(1);
        CountDownLatch resumePreview = new CountDownLatch(1);
        previewHook = () -> {
            previewStarted.countDown();
            try { assertTrue(resumePreview.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        };
        ServerInventoryService service = service();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ServerView> update = executor.submit(() -> service.update(1L,
                new UpdateServer("recovered", null, changed, true, false), 1L));
            assertTrue(previewStarted.await(5, TimeUnit.SECONDS));
            service.disable(1L, 1L);
            assertFalse(rows.get(0).enabled(), "disable must complete before preview resumes");
            resumePreview.countDown();
            ServerView updated = update.get(5, TimeUnit.SECONDS);
            assertFalse(updated.enabled(), "omitted enabled must preserve the concurrent disable");
            assertFalse(rows.get(0).enabled());
            assertEquals("recovered", updated.name());
            assertEquals(changed, updated.hostKeyFingerprint());
            assertEquals("gpu.local", updated.host());
            assertEquals("gpu.local", previewHost);
            assertEquals(22, updated.port());
        } finally {
            resumePreview.countDown();
            executor.shutdownNow();
        }
    }
    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, Handler h) { return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(p,m,a)->h.run(m.getName(),a)); }
    interface Handler { Object run(String method,Object[] args); }
}
