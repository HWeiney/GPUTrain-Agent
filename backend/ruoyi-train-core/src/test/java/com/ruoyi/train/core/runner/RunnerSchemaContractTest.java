package com.ruoyi.train.core.runner;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import com.ruoyi.train.core.runner.mapper.RunnerMapper;
import com.ruoyi.train.core.runner.mapper.RunnerEnrollmentMapper;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;
import com.ruoyi.train.core.runner.mapper.RunnerProcessMapper;
import com.ruoyi.train.core.runner.domain.RunnerEnrollmentRecord;

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
    void schemaDefinesRequiredKeysAndDurableTypes() throws IOException
    {
        String sql = Files.readString(Path.of("../sql/gputrain_runner.sql"));
        assertAll(
            () -> assertTrue(sql.contains("SET NAMES utf8mb4")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_runner_name (runner_name)")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_enrollment_digest (token_digest)")),
            () -> assertTrue(sql.contains("KEY idx_command_poll (runner_id, status, created_at)")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_process_command (command_id)")),
            () -> assertTrue(sql.contains("secret_ciphertext VARCHAR(1024)")),
            () -> assertTrue(sql.contains("payload JSON")),
            () -> assertTrue(sql.contains("result JSON")));
    }

    @Test
    void enrollmentConsumeRequiresDigestAndUnexpiredUnconsumedGrant() throws IOException
    {
        String xml = mapperXml("RunnerEnrollmentMapper");
        assertAll(
            () -> assertTrue(xml.contains("id=\"consume\"")),
            () -> assertTrue(xml.contains("token_digest = #{tokenDigest}")),
            () -> assertTrue(xml.contains("consumed_at IS NULL")),
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
            () -> assertTrue(xml.contains("WHERE command_id = #{commandId}")));
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
            "runner", "linux", "/tmp", "digest", Instant.EPOCH, null, Instant.EPOCH);
        Configuration configuration = new Configuration();
        assertTrue("digest".equals(configuration.newMetaObject(record).getValue("tokenDigest")));
    }

    private String mapperXml(String name) throws IOException
    {
        return Files.readString(Path.of("src/main/resources/mapper/train", name + ".xml"));
    }
}
