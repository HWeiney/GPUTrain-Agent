package com.ruoyi.train.core.runner;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;
import com.ruoyi.train.core.runner.mapper.RunnerEnrollmentMapper;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;
import com.ruoyi.train.core.runner.mapper.RunnerProcessMapper;
import com.ruoyi.train.core.runner.domain.RunnerEnrollmentRecord;
import com.ruoyi.train.core.runner.domain.RunnerCommandRecord;
import com.ruoyi.train.core.runner.domain.RunnerProcessRecord;
import com.ruoyi.train.core.runner.domain.RunnerRecord;

class RunnerSchemaContractTest
{
    @Test
    void schemaContainsDurableTablesAndIdempotencyConstraint() throws IOException
    {
        String sql = Files.readString(Path.of("../sql/gputrain_runner.sql"));
        assertAll(
            () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_runner ")),
            () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_runner_enrollment ")),
            () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_runner_command ")),
            () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_runner_process ")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_runner_idempotency (runner_id, idempotency_key)")));
    }

    @Test
    void runnerPermissionsAreIdempotentAndAdminOnly() throws IOException
    {
        String sql = Files.readString(Path.of("../sql/gputrain_runner.sql"));
        assertAll(
            () -> assertTrue(sql.contains("'train:runner:enroll'")),
            () -> assertTrue(sql.contains("'train:runner:view'")),
            () -> assertTrue(sql.contains("'train:runner:command'")),
            () -> assertTrue(sql.contains("parent.menu_name = 'GPUTrain'")),
            () -> assertTrue(sql.contains("SELECT 1, menu.menu_id")),
            () -> assertTrue(sql.contains("role_menu.role_id = 1")),
            () -> assertTrue(sql.contains("WHERE NOT EXISTS")));
    }

    @Test
    void schemaDefinesRequiredKeysAndDurableTypes() throws IOException
    {
        String sql = Files.readString(Path.of("../sql/gputrain_runner.sql"));
        assertAll(
            () -> assertTrue(sql.contains("SET NAMES utf8mb4")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_runner_name (runner_name)")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_enrollment_digest (token_digest)")),
            () -> assertTrue(sql.contains("token_ciphertext VARCHAR(1024) NULL")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_enrollment_idempotency (idempotency_key)")),
            () -> assertTrue(sql.contains("KEY idx_command_poll (runner_id, status, created_at)")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_process_command (command_id)")),
            () -> assertTrue(sql.contains("secret_ciphertext VARCHAR(1024)")),
            () -> assertTrue(sql.contains("payload JSON")),
            () -> assertTrue(sql.contains("result JSON")));
    }

    @Test
    void enrollmentSchemaUpgradesIntermediateTablesIdempotently() throws IOException
    {
        String sql = Files.readString(Path.of("../sql/gputrain_runner.sql"));
        assertAll(
            () -> assertTrue(sql.contains("information_schema.COLUMNS")),
            () -> assertTrue(sql.contains("ADD COLUMN token_ciphertext VARCHAR(1024) NULL")),
            () -> assertTrue(sql.contains("ADD COLUMN idempotency_key VARCHAR(128)")),
            () -> assertTrue(sql.contains("SET @runner_enrollment_ddl = IF(")),
            () -> assertTrue(sql.contains("UUID()")),
            () -> assertTrue(sql.contains("WHERE idempotency_key IS NULL OR TRIM(idempotency_key) = ''")),
            () -> assertTrue(sql.contains("idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL")),
            () -> assertTrue(sql.contains("MODIFY COLUMN idempotency_key VARCHAR(128)")),
            () -> assertTrue(sql.contains("COLLATE utf8mb4_0900_bin")),
            () -> assertTrue(sql.contains("information_schema.STATISTICS")),
            () -> assertTrue(sql.contains("ADD UNIQUE KEY uk_enrollment_idempotency (idempotency_key)")));
    }

    @Test
    void commandIdempotencyKeyUsesBinaryCollationOnFreshAndExistingTables() throws IOException
    {
        String sql = Files.readString(Path.of("../sql/gputrain_runner.sql"));
        assertAll(
            () -> assertTrue(sql.contains("idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL")),
            () -> assertTrue(sql.contains("TABLE_NAME = 'train_runner_command'")),
            () -> assertTrue(sql.contains("SET @runner_command_ddl = IF(")),
            () -> assertTrue(sql.contains("ALTER TABLE train_runner_command MODIFY COLUMN idempotency_key")),
            () -> assertTrue(sql.contains("COLLATION_NAME <> 'utf8mb4_0900_bin'")));
    }

    @Test
    void enrollmentConsumeRequiresDigestAndUnexpiredUnconsumedGrant() throws IOException
    {
        String xml = mapperXml("RunnerEnrollmentMapper");
        assertAll(
            () -> assertTrue(xml.contains("id=\"consume\"")),
            () -> assertTrue(xml.contains("id=\"findByIdempotencyKey\"")),
            () -> assertTrue(xml.contains("idempotency_key = #{idempotencyKey}")),
            () -> assertTrue(xml.contains("token_digest = #{tokenDigest}")),
            () -> assertTrue(xml.contains("consumed_at IS NULL")),
            () -> assertTrue(xml.contains("SET consumed_at = #{consumedAt}, token_ciphertext = NULL")),
            () -> assertTrue(xml.contains("expires_at &gt; #{consumedAt}")));
    }

    @Test
    void commandLeaseLocksOnlyQueuedOrExpiredLeasedRows() throws IOException
    {
        String xml = mapperXml("RunnerCommandMapper");
        assertAll(
            () -> assertTrue(xml.contains("FOR UPDATE SKIP LOCKED")),
            () -> assertTrue(xml.contains("status = 'QUEUED' OR (status = 'LEASED' AND lease_until &lt; #{now})")),
            () -> assertTrue(xml.contains("ORDER BY created_at, command_id")),
            () -> assertTrue(xml.contains("id=\"markLeased\"")),
            () -> assertTrue(xml.contains("WHERE runner_id = #{runnerId} AND command_id = #{commandId}")),
            () -> assertTrue(xml.contains("attempt_count = #{attempt}")));
    }

    @Test
    void myBatisLoadsEveryMapperStatement() throws IOException
    {
        Configuration configuration = new Configuration();
        for (Class<?> mapper : new Class<?>[] { RunnerMapper.class,
                RunnerEnrollmentMapper.class, RunnerCommandMapper.class, RunnerProcessMapper.class })
        {
            String resource = "mapper/train/" + mapper.getSimpleName() + ".xml";
            try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource))
            {
                assertTrue(stream != null, resource);
                new XMLMapperBuilder(stream, configuration, resource,
                    configuration.getSqlFragments()).parse();
            }
            for (Method method : mapper.getDeclaredMethods())
            {
                if (Modifier.isAbstract(method.getModifiers()))
                    assertTrue(configuration.hasStatement(mapper.getName() + "." + method.getName()),
                        mapper.getSimpleName() + "." + method.getName());
            }
        }
    }

    @Test
    void myBatisCanReadRecordParameters()
    {
        RunnerEnrollmentRecord record = new RunnerEnrollmentRecord(null, 1L,
            "runner", "linux", "/tmp", "digest", "ciphertext", "idempotency-key",
            Instant.EPOCH, null, Instant.EPOCH);
        Configuration configuration = new Configuration();
        assertTrue("digest".equals(configuration.newMetaObject(record).getValue("tokenDigest")));
        assertTrue("ciphertext".equals(configuration.newMetaObject(record).getValue("tokenCiphertext")));
        assertTrue("idempotency-key".equals(configuration.newMetaObject(record).getValue("idempotencyKey")));
    }

    @Test
    void myBatisConstructorTypesMatchRecordConstructors() throws IOException
    {
        Configuration configuration = new Configuration();
        assertConstructorMapping(configuration, RunnerMapper.class, RunnerRecord.class, "RunnerResult");
        assertConstructorMapping(configuration, RunnerEnrollmentMapper.class,
            RunnerEnrollmentRecord.class, "EnrollmentResult");
        assertConstructorMapping(configuration, RunnerCommandMapper.class,
            RunnerCommandRecord.class, "CommandResult");
        assertConstructorMapping(configuration, RunnerProcessMapper.class,
            RunnerProcessRecord.class, "ProcessResult");
    }

    private void assertConstructorMapping(Configuration configuration, Class<?> mapper,
            Class<?> record, String resultMapId) throws IOException
    {
        String resource = "mapper/train/" + mapper.getSimpleName() + ".xml";
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource))
        {
            assertTrue(stream != null, resource);
            new XMLMapperBuilder(stream, configuration, resource,
                configuration.getSqlFragments()).parse();
        }
        ResultMap resultMap = configuration.getResultMap(mapper.getName() + "." + resultMapId);
        Class<?>[] argumentTypes = resultMap.getConstructorResultMappings().stream()
            .map(ResultMapping::getJavaType).toArray(Class<?>[]::new);
        assertDoesNotThrow(() -> record.getDeclaredConstructor(argumentTypes), record.getSimpleName());
    }

    private String mapperXml(String name) throws IOException
    {
        return Files.readString(Path.of("src/main/resources/mapper/train", name + ".xml"));
    }
}
