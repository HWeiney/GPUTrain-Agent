package com.ruoyi.train.core.server.service;

import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.TransactionDefinition;
import com.ruoyi.train.core.server.domain.*;
import com.ruoyi.train.core.server.mapper.*;
import com.ruoyi.train.core.server.ssh.*;
import com.ruoyi.train.core.server.ssh.ServerProbeTest.FixtureGateway;
import static com.ruoyi.train.core.server.ssh.ServerProbeTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerProbeServiceTest
{
    static class MutableClock extends Clock {
        Instant now = NOW;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    static class Store {
        Map<Long, ServerRecord> servers = new ConcurrentHashMap<>(Map.of(1L, server(1), 2L, server(2)));
        Map<Long, ServerAccountRecord> accounts = new ConcurrentHashMap<>(Map.of(11L, account(1,11), 22L, account(2,22)));
        Map<List<Object>, ServerSnapshotRecord> snapshots = new ConcurrentHashMap<>();
        Map<List<Object>, ServerGpuDeviceRecord> devices = new ConcurrentHashMap<>();
        Map<List<Object>, ServerGpuSnapshotRecord> gpuSnapshots = new ConcurrentHashMap<>();
        int outcomeWrites;
        @SuppressWarnings("unchecked") <T> T mapper(Class<T> type) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                String name = method.getName();
                if (type == ServerMapper.class && name.equals("findById")) return servers.get(args[0]);
                if (type == ServerAccountMapper.class) {
                    if (name.equals("findById") || name.equals("findCredentialsByAuthorizedAccountId")) return accounts.get(args[0]);
                    if (name.equals("listEnabledForProbe")) return accounts.values().stream().filter(a -> a.accountId() > (Long) args[0]).sorted(Comparator.comparing(ServerAccountRecord::accountId)).limit((Integer) args[1]).toList();
                    if (name.equals("updateProbeOutcome")) {
                        outcomeWrites++;
                        var a = accounts.get(args[0]);
                        accounts.put(a.accountId(), new ServerAccountRecord(a.accountId(), a.serverId(), a.ownerUserId(), a.linuxUsername(), a.authType(), a.credentialCiphertext(), a.passphraseCiphertext(), a.hasCredential(), a.allowedRoot(), a.profileJson(), a.enabled(), (String) args[1], (String) args[2], (Instant) args[3], (Boolean) args[4] ? (Instant) args[3] : a.lastSuccessAt(), a.createdAt(), (Instant) args[3]));
                        return 1;
                    }
                }
                if (type == ServerSnapshotMapper.class) {
                    if (name.equals("insert")) { var s=(ServerSnapshotRecord)args[0]; snapshots.put(List.of(s.serverId(),s.accountId()),s); return 1; }
                    if (name.equals("findLatest")) return snapshots.get(List.of(args));
                }
                if (type == ServerGpuDeviceMapper.class) {
                    if (name.equals("upsert")) { var s=(ServerGpuDeviceRecord)args[0]; devices.put(List.of(s.serverId(),s.gpuUuid()),s); return 1; }
                    if (name.equals("findByUuid")) return devices.get(List.of(args));
                    if (name.equals("listByServer")) return devices.values().stream().filter(d->d.serverId().equals(args[0])).toList();
                }
                if (type == ServerGpuSnapshotMapper.class) {
                    if (name.equals("insert")) { var s=(ServerGpuSnapshotRecord)args[0]; gpuSnapshots.put(List.of(s.serverId(),s.accountId(),s.gpuUuid()),s); return 1; }
                    if (name.equals("findLatest")) return gpuSnapshots.get(List.of(args));
                }
                throw new AssertionError(type + " " + name);
            });
        }
    }
    static AbstractPlatformTransactionManager transactions() {
        return new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        };
    }
    private final Store store = new Store();
    private final MutableClock clock = new MutableClock();
    private final FixtureGateway gateway = new FixtureGateway();
    private ServerProbeService service() {
        return new ServerProbeService(store.mapper(ServerMapper.class), store.mapper(ServerAccountMapper.class), store.mapper(ServerSnapshotMapper.class), store.mapper(ServerGpuDeviceMapper.class), store.mapper(ServerGpuSnapshotMapper.class), new ServerProbe(gateway), transactions(), clock);
    }
    private ServerDeviceQueryService query() {
        return new ServerDeviceQueryService(store.mapper(ServerMapper.class), store.mapper(ServerAccountMapper.class), store.mapper(ServerSnapshotMapper.class), store.mapper(ServerGpuDeviceMapper.class), store.mapper(ServerGpuSnapshotMapper.class), clock);
    }

    @Test void onlineAndOfflineServersWithSameUuidRetainIsolatedLastSuccessfulSnapshots() {
        try (var service = service()) {
            service.probe(1L,11L); service.probe(2L,22L);
            assertEquals(2, store.devices.size());
            assertEquals(2, store.gpuSnapshots.size());
            clock.advance(10); gateway.failure = SshGateway.ErrorCode.CONNECT_TIMEOUT;
            var failed = service.probe(2L,22L);
            assertEquals("OFFLINE", failed.status());
            assertEquals("CONNECT_TIMEOUT", failed.errorCode());
            assertEquals(NOW, store.snapshots.get(List.of(2L,22L)).sampledAt());
            assertEquals(NOW, store.accounts.get(22L).lastSuccessAt());
            assertEquals(clock.instant(), store.accounts.get(22L).lastProbeAt());
            assertEquals("ONLINE", store.accounts.get(11L).status());
            assertEquals("EXTERNAL_OCCUPIED", query().list(1L,11L,7L).get(0).identityState());
            assertEquals(1, query().list(1L,11L,7L).get(0).externalProcessCount());
            assertEquals("UNOBSERVABLE", query().list(2L,22L,7L).get(0).identityState());
            assertEquals(NOW, query().list(2L,22L,7L).get(0).sampledAt());
        }
    }

    @Test void accountCadenceAndFailureBackoffPreventTightRetries() {
        try (var service = service()) {
            gateway.failure = SshGateway.ErrorCode.CONNECT_TIMEOUT;
            service.probe(1L,11L); clock.advance(9); service.probe(1L,11L);
            assertEquals(1, gateway.calls);
            clock.advance(1); service.probe(1L,11L);
            assertEquals(2, gateway.calls);
            clock.advance(10); service.probe(1L,11L);
            assertEquals(2, gateway.calls, "second failure backs off to 20 seconds");
            clock.advance(10); gateway.failure = null; service.probe(1L,11L);
            assertEquals(3, gateway.calls);
            assertEquals("ONLINE", store.accounts.get(11L).status());
            assertEquals(3, store.outcomeWrites);
        }
    }

    @Test void hostKeyChangeNeverReplacesSuccessfulFacts() {
        try (var service = service()) {
            service.probe(1L,11L); clock.advance(10);
            gateway.failure = SshGateway.ErrorCode.HOST_KEY_CHANGED;
            assertEquals("HOST_KEY_CHANGED", service.probe(1L,11L).status());
            assertEquals(NOW, store.snapshots.get(List.of(1L,11L)).sampledAt());
            assertEquals(NOW, store.gpuSnapshots.get(List.of(1L,11L,"GPU-same")).sampledAt());
        }
    }

    @Test void deviceReadsEnforceOwnerAndAccountServerScope() {
        try (var service = service()) {
            service.probe(1L,11L);
            assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> query().list(1L,11L,99L));
            assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> query().list(2L,11L,7L));
            assertTrue(query().list(2L,22L,7L).isEmpty());
            assertThrows(com.ruoyi.common.exception.ServiceException.class, () -> service.probe(2L,11L));
            assertEquals(1, gateway.calls);
        }
    }

    @Test void newGpuUnavailableSampleMakesOldGpuFactsUnobservable() {
        try (var service = service()) {
            service.probe(1L,11L); clock.advance(10);
            gateway.replies.replaceAll((command, reply) -> command.get(0).equals("nvidia-smi") ? new SshGateway.CommandResult(127,"","") : reply);
            service.probe(1L,11L);
            var device = query().list(1L,11L,7L).get(0);
            assertFalse(device.schedulable());
            assertEquals("UNOBSERVABLE", device.identityState());
            assertEquals(NOW, device.sampledAt());
        }
    }
    @Test void oversizedProcessEvidenceUpdatesFailureOnly() {
        try (var service = service()) {
            service.probe(1L,11L); clock.advance(10);
            StringBuilder csv = new StringBuilder();
            for (int i=1; i<=250; i++) csv.append("GPU-same, ").append(i).append(", ").append("p".repeat(200)).append(", 1\n");
            gateway.replies.replaceAll((command, reply) -> command.get(1).startsWith("--query-compute-apps") ? new SshGateway.CommandResult(0,csv.toString(),"") : reply);
            var result = service.probe(1L,11L);
            assertEquals("OUTPUT_LIMIT", result.errorCode());
            assertEquals(NOW, store.accounts.get(11L).lastSuccessAt());
            assertEquals(clock.instant(), store.accounts.get(11L).lastProbeAt());
            assertEquals(NOW, store.snapshots.get(List.of(1L,11L)).sampledAt());
        }
    }

    @Test void disabledServerMakesFreshIdleGpuUnobservableWithoutReplacingFacts() {
        try (var service = service()) {
            gateway.replies.replaceAll((command, reply) -> command.get(1).startsWith("--query-compute-apps") ? new SshGateway.CommandResult(0,"","") : reply);
            service.probe(1L,11L);
            var before = query().list(1L,11L,7L).get(0);
            assertEquals("OBSERVED", before.identityState());
            assertTrue(before.schedulable());
            ServerRecord server = store.servers.get(1L);
            store.servers.put(1L, new ServerRecord(server.serverId(),server.name(),server.host(),server.port(),server.hostKeySha256(),false,server.createdAt(),clock.instant()));
            var after = query().list(1L,11L,7L).get(0);
            assertEquals("UNOBSERVABLE", after.identityState());
            assertFalse(after.schedulable());
            assertEquals(before.sampledAt(), after.sampledAt());
            assertEquals(before.totalMemoryMiB(), after.totalMemoryMiB());
            assertEquals(before.usedMemoryMiB(), after.usedMemoryMiB());
            assertEquals(before.utilizationPercent(), after.utilizationPercent());
            assertEquals(before.migMode(), after.migMode());
        }
    }

    @Test void staleEvidenceDoesNotBecomeSchedulable() {
        try (var service = service()) {
            gateway.replies.replaceAll((command, reply) -> command.get(1).startsWith("--query-compute-apps") ? new SshGateway.CommandResult(0,"","") : reply);
            service.probe(1L,11L);
            assertTrue(query().list(1L,11L,7L).get(0).schedulable());
            clock.advance(31);
            assertFalse(query().list(1L,11L,7L).get(0).schedulable());
        }
    }

    @Test void limitsConcurrentSessionsToEightAndSerializesSameAccount() throws Exception {
        CountDownLatch entered = new CountDownLatch(8), release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        FixtureGateway blocking = new FixtureGateway() {
            @Override public <T> T withVerifiedSession(ServerRecord server, ServerAccountRecord account,
                java.util.function.Function<SshGateway.SshSession,T> operation) {
                int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max); entered.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); return super.withVerifiedSession(server,account,operation); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                finally { active.decrementAndGet(); }
            }
        };
        for (long id=1;id<=9;id++) store.accounts.put(id,account(1,id));
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try (var service = new ServerProbeService(store.mapper(ServerMapper.class),store.mapper(ServerAccountMapper.class),store.mapper(ServerSnapshotMapper.class),store.mapper(ServerGpuDeviceMapper.class),store.mapper(ServerGpuSnapshotMapper.class),new ServerProbe(blocking),transactions(),clock)) {
            List<Future<ServerProbeResult>> futures = new ArrayList<>();
            for (long id=1;id<=8;id++) { long accountId=id; futures.add(callers.submit(()->service.probe(1L,accountId))); }
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals("CONCURRENCY_LIMIT",service.probe(1L,9L).errorCode());
            assertEquals("PROBE_THROTTLED",service.probe(1L,1L).errorCode());
            release.countDown();
            for (var future:futures) assertEquals("ONLINE",future.get(5,TimeUnit.SECONDS).status());
            assertEquals(8,peak.get());
        } finally { release.countDown(); callers.shutdownNow(); }
    }

}
