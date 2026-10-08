package com.ruoyi.train.core.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.framework.config.CredentialSafeDruidConfiguration;
import com.ruoyi.train.core.server.domain.ServerAccountRecord;
import com.ruoyi.train.core.server.domain.ServerGpuDeviceRecord;
import com.ruoyi.train.core.server.domain.ServerGpuSnapshotRecord;
import com.ruoyi.train.core.server.domain.ServerRecord;
import com.ruoyi.train.core.server.domain.ServerSnapshotRecord;
import com.ruoyi.train.core.server.mapper.ServerAccountMapper;
import com.ruoyi.train.core.server.mapper.ServerGpuDeviceMapper;
import com.ruoyi.train.core.server.mapper.ServerGpuSnapshotMapper;
import com.ruoyi.train.core.server.mapper.ServerMapper;
import com.ruoyi.train.core.server.mapper.ServerSnapshotMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.spring.boot3.autoconfigure.stat.DruidFilterConfiguration;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class ServerSchemaContractTest
{
    private static final Path MIGRATION = Path.of("../sql/gputrain_phase8_ssh_servers.sql");
    private static final String PACKAGE = "com.ruoyi.train.core.server.";
    private static final List<String> NAMES = List.of("Server", "ServerAccount", "ServerSnapshot",
            "ServerGpuDevice", "ServerGpuSnapshot");

    @Test
    void migrationAddsInventoryWithoutChangingRunnerTables() throws Exception
    {
        assertTrue(Files.isRegularFile(MIGRATION), "SSH inventory migration must exist");
        String sql = Files.readString(MIGRATION);
        for (String table : List.of("train_server", "train_server_account", "train_server_snapshot",
                "train_server_gpu_device", "train_server_gpu_snapshot"))
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + table + " ("), table);
        assertAll(
            () -> assertTrue(sql.contains("UNIQUE KEY uk_server_host_port (host,port)")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_server_account (server_id,owner_user_id,linux_username)")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_server_gpu (server_id,gpu_uuid)")),
            () -> assertTrue(sql.contains("host VARCHAR(255)")),
            () -> assertTrue(sql.contains("host_key_sha256 VARCHAR(128)")),
            () -> assertTrue(sql.contains("credential_ciphertext")),
            () -> assertTrue(sql.contains("passphrase_ciphertext")),
            () -> assertTrue(sql.contains("last_success_at")),
            () -> assertTrue(sql.contains("FOREIGN KEY (server_id,account_id) REFERENCES train_server_account (server_id,account_id)")),
            () -> assertTrue(sql.contains("KEY idx_server_snapshot (server_id,account_id,sampled_at)")),
            () -> assertTrue(sql.contains("KEY idx_server_gpu_snapshot (server_id,account_id,gpu_uuid,sampled_at)")),
            () -> assertTrue(sql.contains("OCTET_LENGTH(CAST(facts_json AS CHAR)) <= 65536")),
            () -> assertFalse(sql.matches("(?is).*\\b(?:DROP|ALTER|TRUNCATE)\\s+TABLE\\b.*")),
            () -> assertFalse(sql.contains("train_runner")));
    }

    @Test
    void composeMountsEighthMigration() throws Exception
    {
        String compose = Files.readString(Path.of("../../deploy/compose.yaml"));
        assertTrue(compose.contains("../backend/sql/gputrain_phase8_ssh_servers.sql:/docker-entrypoint-initdb.d/08-gputrain-ssh-servers.sql:ro"));
    }

    @Test
    void myBatisLoadsEveryStatementAndRecordConstructor() throws Exception
    {
        Configuration configuration = configuration();
        for (String name : NAMES)
        {
            Class<?> mapper = Class.forName(PACKAGE + "mapper." + name + "Mapper");
            for (Method method : mapper.getDeclaredMethods())
                assertTrue(configuration.hasStatement(mapper.getName() + "." + method.getName()), method.toString());
            Class<?> record = Class.forName(PACKAGE + "domain." + name + "Record");
            for (var map : configuration.getResultMaps())
            {
                if (!map.getId().startsWith(mapper.getName() + ".")) continue;
                Class<?>[] types = map.getConstructorResultMappings().stream()
                    .map(ResultMapping::getJavaType).toArray(Class<?>[]::new);
                assertDoesNotThrow(() -> record.getDeclaredConstructor(types), map.getId());
            }
        }
    }

    @Test
    void readQueriesExcludeSecretsExceptAuthorizedSingleAccountLookup() throws Exception
    {
        Configuration configuration = configuration();
        for (String name : NAMES)
        {
            String namespace = PACKAGE + "mapper." + name + "Mapper";
            Class<?> mapper = Class.forName(namespace);
            for (Method method : mapper.getDeclaredMethods())
            {
                MappedStatement statement = configuration.getMappedStatement(namespace + "." + method.getName());
                if (!statement.getSqlCommandType().name().equals("SELECT")) continue;
                String sql = statement.getBoundSql(Map.of("serverId", 1L, "accountId", 1L,
                    "ownerUserId", 2L, "isAdmin", false, "authorizedAccountId", 3L,
                    "gpuUuid", "GPU-1", "limit", 20)).getSql().replaceAll("\\s+", " ");
                assertFalse(sql.contains("*"), "explicit projections required: " + statement.getId());
                if (method.getName().equals("findCredentialsByAuthorizedAccountId"))
                {
                    assertTrue(sql.contains("credential_ciphertext"));
                    assertTrue(sql.contains("passphrase_ciphertext"));
                    assertTrue(sql.contains("WHERE account_id = ?"));
                    assertEquals(1, method.getParameterCount());
                    assertEquals("authorizedAccountId", statement.getBoundSql(Map.of("authorizedAccountId", 3L))
                        .getParameterMappings().get(0).getProperty());
                }
                else
                {
                    assertFalse(sql.contains("credential_ciphertext"), statement.getId());
                    assertFalse(sql.contains("passphrase_ciphertext"), statement.getId());
                }
            }
        }
        String accountSql = configuration.getMappedStatement(PACKAGE + "mapper.ServerAccountMapper.listForOwner")
            .getBoundSql(Map.of("serverId", 1L, "ownerUserId", 2L)).getSql();
        assertTrue(accountSql.contains("owner_user_id = ?"));
    }

    @Test
    void secretMapperSqlParameterAndResultLoggingIsDisabledInApplicationConfig() throws Exception
    {
        String yaml = Files.readString(Path.of("../ruoyi-admin/src/main/resources/application.yml"));
        assertTrue(yaml.contains("com.ruoyi.train.core.server.mapper.ServerAccountMapper: warn"),
            "Credential mapper needs a specific WARN override to suppress MyBatis DEBUG/TRACE secrets");
    }

    @Test
    void datasourceInstallsOnlyCredentialSafeStatFilter() throws Exception
    {
        try (AnnotationConfigApplicationContext context = druidFilterContext())
        {
            var filters = context.getBeansOfType(StatFilter.class);
            assertEquals(1, filters.size(), "a second stock StatFilter would still retain credentials");
            StatFilter filter = filters.values().iterator().next();
            assertNotEquals(StatFilter.class, filter.getClass(), "replace stock parameter retention before logging/statistics");
            assertTrue(filter.isLogSlowSql(), "keep configured slow SQL diagnostics enabled");
            assertTrue(filter.isMergeSql());
            assertEquals(1000, filter.getSlowSqlMillis());
        }
    }

    @Test
    void accountSerializationAndLoggingExcludeBothCiphertexts() throws Exception
    {
        ServerAccountRecord account = new ServerAccountRecord(1L, 2L, 3L, "linux", "PRIVATE_KEY",
            "credential-secret-value", "passphrase-secret-value", true, "/tmp", null,
            true, "UNKNOWN", null, null, null, null, null);
        String json = new ObjectMapper().writeValueAsString(account);
        for (String secret : List.of("credentialCiphertext", "passphraseCiphertext",
                "credential-secret-value", "passphrase-secret-value"))
        {
            assertFalse(json.contains(secret), json);
            assertFalse(account.toString().contains(secret), account.toString());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "mysql.it", matches = "true")
    void realMySqlMigrationIsRepeatableAndEnforcesIdentityAndAccountScope() throws Exception
    {
        String url = System.getProperty("mysql.it.url");
        Connection connection;
        try
        {
            connection = DriverManager.getConnection(url, System.getProperty("mysql.it.user"),
                System.getProperty("mysql.it.password"));
        }
        catch (SQLException unavailable)
        {
            Assumptions.assumeTrue(false, "MySQL integration database unavailable: " + unavailable.getSQLState());
            return;
        }
        String database = "server_schema_it_" + UUID.randomUUID().toString().replace("-", "");
        try (connection; Statement statement = connection.createStatement())
        {
            statement.execute("CREATE DATABASE " + database);
            try
            {
                statement.execute("USE " + database);
                statement.execute("CREATE TABLE train_runner (runner_id BIGINT PRIMARY KEY, marker VARCHAR(32))");
                statement.executeUpdate("INSERT INTO train_runner VALUES (1,'untouched')");
                String sql = Files.readString(MIGRATION).replaceAll("(?m)^--.*$", "");
                for (int pass = 0; pass < 2; pass++)
                    for (String command : sql.split(";"))
                        if (!command.isBlank()) statement.execute(command);
                statement.executeUpdate("INSERT INTO train_server (server_id,name,host,port,host_key_sha256,created_at,updated_at) VALUES (1,'one','host',22,'SHA256:test',NOW(3),NOW(3)),(2,'two','host2',22,'SHA256:test2',NOW(3),NOW(3))");
                assertEquals(1062, assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO train_server (name,host,port,host_key_sha256,created_at,updated_at) VALUES ('duplicate','host',22,'SHA256:test',NOW(3),NOW(3))")).getErrorCode());
                statement.executeUpdate("INSERT INTO train_server_account (account_id,server_id,owner_user_id,linux_username,auth_type,credential_ciphertext,allowed_root,created_at,updated_at) VALUES (1,1,1,'linux','PASSWORD','cipher','/tmp',NOW(3),NOW(3)),(2,2,1,'linux','PASSWORD','cipher','/tmp',NOW(3),NOW(3))");
                assertEquals(1062, assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO train_server_account (server_id,owner_user_id,linux_username,auth_type,credential_ciphertext,allowed_root,created_at,updated_at) VALUES (1,1,'linux','PASSWORD','cipher','/tmp',NOW(3),NOW(3))")).getErrorCode());
                statement.executeUpdate("INSERT INTO train_server_gpu_device (server_id,gpu_uuid,name,created_at,updated_at) VALUES (1,'GPU-same','A100',NOW(3),NOW(3)),(2,'GPU-same','A100',NOW(3),NOW(3))");
                assertEquals(1062, assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO train_server_gpu_device (server_id,gpu_uuid,name,created_at,updated_at) VALUES (1,'GPU-same','A100',NOW(3),NOW(3))")).getErrorCode());
                assertEquals(1452, assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO train_server_snapshot (server_id,account_id,sampled_at,status,facts_json,created_at) VALUES (1,2,NOW(3),'ONLINE',JSON_OBJECT(),NOW(3))")).getErrorCode());
                assertEquals(3819, assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO train_server_snapshot (server_id,account_id,sampled_at,status,facts_json,created_at) VALUES (1,1,NOW(3),'ONLINE',JSON_OBJECT('value',REPEAT('x',65536)),NOW(3))")).getErrorCode());
                try (var rows = statement.executeQuery("SELECT marker FROM train_runner WHERE runner_id=1"))
                {
                    assertTrue(rows.next());
                    assertEquals("untouched", rows.getString(1));
                }
                assertMapperRoundTrips(database);
            }
            finally
            {
                statement.execute("DROP DATABASE " + database);
            }
        }
    }

    private void assertMapperRoundTrips(String database) throws Exception
    {
        Configuration configuration = configuration();
        Logger statLogger = (Logger) LoggerFactory.getLogger(StatFilter.class);
        ListAppender<ILoggingEvent> capturedLogs = new ListAppender<>();
        capturedLogs.start();
        statLogger.addAppender(capturedLogs);
        try (AnnotationConfigApplicationContext context = druidFilterContext();
                DruidDataSource dataSource = new DruidDataSource())
        {
            dataSource.setUrl(System.getProperty("mysql.it.url"));
            dataSource.setUsername(System.getProperty("mysql.it.user"));
            dataSource.setPassword(System.getProperty("mysql.it.password"));
            dataSource.setInitialSize(0);
            dataSource.setMinIdle(0);
            dataSource.setMaxActive(2);
            StatFilter statFilter = context.getBean(StatFilter.class);
            statFilter.setSlowSqlMillis(0); // every statement reaches retention and ERROR logging
            dataSource.getProxyFilters().add(statFilter);
            configuration.setEnvironment(new Environment("schema-it", new JdbcTransactionFactory(), dataSource));
            try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession())
            {
                session.getConnection().setCatalog(database);
                ServerMapper servers = session.getMapper(ServerMapper.class);
                assertEquals("host", servers.findById(1L).host());
                assertEquals(2, servers.listForOwner(1L, false).size());
                assertEquals(0, servers.listForOwner(99L, false).size());
                assertEquals(2, servers.listForOwner(99L, true).size());
                Instant sampledAt = Instant.parse("2026-10-07T00:00:00Z");
                assertEquals(1, servers.insert(new ServerRecord(null, "three", "host3", 22,
                    "SHA256:three", true, sampledAt, sampledAt)));
                Long thirdId = servers.listForOwner(99L, true).stream()
                    .filter(server -> server.host().equals("host3")).findFirst().orElseThrow().serverId();
                assertEquals(1, servers.setEnabled(thirdId, false, sampledAt));
                assertFalse(servers.findById(thirdId).enabled());

                ServerAccountMapper accounts = session.getMapper(ServerAccountMapper.class);
                ServerAccountRecord publicAccount = accounts.findById(1L);
                assertTrue(publicAccount.hasCredential());
                assertNull(publicAccount.credentialCiphertext());
                assertNull(publicAccount.passphraseCiphertext());
                assertEquals(1, accounts.listForOwner(1L, 1L).size());
                assertEquals(0, accounts.listForOwner(1L, 99L).size());
                assertNull(accounts.listForOwner(1L, 1L).get(0).credentialCiphertext());
                assertEquals("cipher", accounts.findCredentialsByAuthorizedAccountId(1L).credentialCiphertext());
                assertEquals(1, accounts.insert(new ServerAccountRecord(null, 1L, 2L, "other", "PRIVATE_KEY",
                    "private-key-cipher", "passphrase-cipher", true, "/tmp", "{}", true, "UNKNOWN",
                    null, null, null, sampledAt, sampledAt)));
                Long otherAccountId = accounts.listForOwner(1L, 2L).get(0).accountId();
                assertEquals(1, accounts.updateCredentials(otherAccountId, "rotated-cipher", null, sampledAt));
                assertEquals("rotated-cipher", accounts.findCredentialsByAuthorizedAccountId(otherAccountId).credentialCiphertext());
                assertNull(accounts.findCredentialsByAuthorizedAccountId(otherAccountId).passphraseCiphertext());
                assertEquals(1, accounts.updateProbeOutcome(1L, "ONLINE", null, sampledAt, true));
                assertEquals(1, accounts.updateProbeOutcome(1L, "OFFLINE", "CONNECT_TIMEOUT", sampledAt.plusSeconds(10), false));
                assertEquals(sampledAt, accounts.findById(1L).lastSuccessAt());
                assertEquals(sampledAt.plusSeconds(10), accounts.findById(1L).lastProbeAt());
                assertEquals("OFFLINE", accounts.findById(1L).status());

                ServerSnapshotMapper snapshots = session.getMapper(ServerSnapshotMapper.class);
                assertEquals(1, snapshots.insert(new ServerSnapshotRecord(null, 1L, 1L, sampledAt,
                    "ONLINE", null, "{\"ssh\":true}", "{\"load1\":0.5}", sampledAt)));
                assertTrue(snapshots.findLatest(1L, 1L).factsJson().contains("load1"));
                assertNull(snapshots.findLatest(2L, 1L));

                ServerGpuDeviceMapper devices = session.getMapper(ServerGpuDeviceMapper.class);
                assertEquals("A100", devices.findByUuid(1L, "GPU-same").name());
                assertTrue(devices.upsert(new ServerGpuDeviceRecord(null, 1L, "GPU-same", "renamed",
                    "OBSERVED", false, sampledAt, sampledAt)) > 0);
                assertEquals("renamed", devices.listByServer(1L).get(0).name());
                assertEquals("A100", devices.listByServer(2L).get(0).name());

                ServerGpuSnapshotMapper gpuSnapshots = session.getMapper(ServerGpuSnapshotMapper.class);
                assertEquals(1, gpuSnapshots.insert(new ServerGpuSnapshotRecord(null, 1L, 1L, "GPU-same",
                    0, 80000, 1000, 10, "DISABLED", "550", "[]", sampledAt, sampledAt)));
                assertEquals(80000, gpuSnapshots.findLatest(1L, 1L, "GPU-same").totalMemoryMiB());
                assertEquals("[]", gpuSnapshots.findLatest(1L, 1L, "GPU-same").computeProcessesJson());
                assertNull(gpuSnapshots.findLatest(2L, 2L, "GPU-same"));
                try (var plainSql = session.getConnection().prepareStatement("SELECT ? AS diagnostic"))
                {
                    plainSql.setString(1, "visible-stat-value");
                    try (var rows = plainSql.executeQuery())
                    {
                        assertTrue(rows.next());
                    }
                }
                session.commit();
            }
            var statistics = dataSource.getDataSourceStat().getSqlStatMap().values();
            assertTrue(statistics.stream().anyMatch(stat -> stat.getSql().contains("train_server_account")));
            String retainedParameters = statistics.stream().map(stat -> String.valueOf(stat.getLastSlowParameters()))
                .collect(java.util.stream.Collectors.joining("\n"));
            for (String secret : List.of("private-key-cipher", "passphrase-cipher", "rotated-cipher"))
                assertFalse(retainedParameters.contains(secret), "Druid statistics retained secret " + secret);
            for (var stat : statistics)
            {
                if (stat.getSql().toLowerCase(java.util.Locale.ROOT).contains("train_server_account"))
                    assertEquals("[]", stat.getLastSlowParameters(), stat.getSql());
            }
            assertTrue(statistics.stream().anyMatch(stat -> stat.getLastSlowParameters() != null
                && stat.getLastSlowParameters().contains("visible-stat-value")), "ordinary SQL keeps diagnostics");
            String logs = capturedLogs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(logs.contains("slow sql"), "exercise actual ERROR-level slow SQL logs");
            for (String secret : List.of("private-key-cipher", "passphrase-cipher", "rotated-cipher"))
                assertFalse(logs.contains(secret), "Druid slow SQL log exposed secret " + secret);
        }
        finally
        {
            statLogger.detachAppender(capturedLogs);
            capturedLogs.stop();
        }
    }

    private AnnotationConfigApplicationContext druidFilterContext() throws Exception
    {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("../ruoyi-admin/src/main/resources/application-druid.yml"));
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource("druid", yaml.getObject()));
        ConfigurationPropertiesBindingPostProcessor.register(context);
        context.register(CredentialSafeDruidConfiguration.class);
        context.register(DruidFilterConfiguration.class);
        context.refresh();
        return context;
    }

    private Configuration configuration() throws Exception
    {
        Configuration configuration = new Configuration();
        for (String name : NAMES)
        {
            String resource = "mapper/train/" + name + "Mapper.xml";
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource))
            {
                assertNotNull(stream, resource);
                new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }
}
