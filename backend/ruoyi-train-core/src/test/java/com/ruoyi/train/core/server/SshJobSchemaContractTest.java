package com.ruoyi.train.core.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.annotation.MapperScan;
import com.ruoyi.framework.config.ApplicationConfig;
import com.ruoyi.train.core.server.gpu.SshGpuLeaseMapper;
import com.ruoyi.train.core.server.gpu.SshGpuLeaseRecord;
import org.junit.jupiter.api.Test;

class SshJobSchemaContractTest
{
    private static final Path MIGRATION = Path.of("../sql/gputrain_phase9_ssh_jobs.sql");

    @Test
    void springMapperScanIncludesSshGpuLeaseMapperPackage()
    {
        MapperScan scan = ApplicationConfig.class.getAnnotation(MapperScan.class);
        assertNotNull(scan);
        assertTrue(List.of(scan.value()).contains(SshGpuLeaseMapper.class.getPackageName()),
                "SshGpuLeaseMapper package must be included in Spring's mapper scan");
    }

    @Test
    void migrationIsAdditiveAndKeepsRunnerHistoryReadable() throws Exception
    {
        assertTrue(Files.isRegularFile(MIGRATION), "Phase 9 migration must exist");
        String sql = Files.readString(MIGRATION);
        for (String column : List.of("execution_mode", "server_id", "account_id", "remote_unit_name", "remote_working_directory"))
            assertTrue(sql.contains(column), column);
        for (String table : List.of("train_gpu_lease", "train_job_log", "train_job_metric", "train_runner_command"))
            assertFalse(sql.matches("(?is).*\\bDROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?" + table + "\\b.*"), table);
        assertAll(
            () -> assertTrue(sql.contains("'RUNNER_LEGACY'")),
            () -> assertTrue(sql.contains("train_server_gpu_device")),
            () -> assertTrue(sql.contains("train_server_gpu_snapshot")),
            () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_server_gpu_lease (")),
            () -> assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS train_server_gpu_device (")),
            () -> assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS train_server_gpu_snapshot (")),
            () -> assertTrue(sql.contains("CONCAT(server_id,':',gpu_uuid)")),
            () -> assertTrue(sql.contains("VARCHAR(160)")),
            () -> assertTrue(sql.contains("status IN ('ACTIVE','QUARANTINED')")),
            () -> assertTrue(sql.contains("UNIQUE KEY uk_active_server_gpu")),
            () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_ssh_job_log")),
            () -> assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS train_ssh_job_metric")));
    }

    @Test
    void freshInstallUsesSshSchemaWithoutRunnerTables() throws Exception
    {
        String compose = Files.readString(Path.of("../../deploy/compose.yaml"));
        String baseline = Files.readString(Path.of("../sql/gputrain_ssh_baseline.sql"));
        assertTrue(baseline.contains("CREATE TABLE IF NOT EXISTS train_job ("));
        assertTrue(baseline.contains("CREATE TABLE IF NOT EXISTS train_job_alert ("));
        assertFalse(compose.contains("gputrain_runner.sql"));
        assertFalse(compose.contains("gputrain_phase3.sql"));
        assertFalse(compose.contains("gputrain_phase4.sql"));
        assertTrue(compose.indexOf("gputrain_ssh_baseline.sql") < compose.indexOf("gputrain_phase8_ssh_servers.sql"));
        assertTrue(compose.indexOf("gputrain_phase8_ssh_servers.sql") < compose.indexOf("gputrain_phase9_ssh_jobs.sql"));
    }

    @Test
    void jobRecordAndMapperCarryExecutionIdentityWithoutLosingRunnerFields() throws Exception
    {
        Set<String> components = java.util.Arrays.stream(
                com.ruoyi.train.core.job.domain.JobRecord.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName).collect(Collectors.toSet());
        assertTrue(components.containsAll(Set.of("executionMode", "serverId", "accountId", "remoteUnitName", "remoteWorkingDirectory")));
        assertTrue(components.containsAll(Set.of("runnerId", "startCommandId", "stopCommandId")));

        Configuration configuration = new Configuration();
        String resource = "mapper/train/JobMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource))
        {
            assertNotNull(input, resource);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        assertTrue(configuration.hasStatement("com.ruoyi.train.core.job.mapper.JobMapper.findById"));
        assertTrue(configuration.hasStatement("com.ruoyi.train.core.job.mapper.JobMapper.listDueSshJobs"));
        assertTrue(configuration.hasStatement("com.ruoyi.train.core.job.mapper.JobMapper.listSshToStart"));
        assertTrue(configuration.hasStatement("com.ruoyi.train.core.job.mapper.JobMapper.listSshForReconciliation"));
        assertTrue(configuration.hasStatement("com.ruoyi.train.core.job.mapper.JobMapper.listSshJobsForLogs"));
        assertTrue(configuration.getMappedStatement("com.ruoyi.train.core.job.mapper.JobMapper.findById")
            .getBoundSql(java.util.Map.of("jobId", 1L)).getSql().contains("SELECT"));
        String insertSql = configuration.getMappedStatement("com.ruoyi.train.core.job.mapper.JobMapper.insert")
            .getBoundSql(new com.ruoyi.train.core.job.domain.JobRecord(null, "uuid", 1L, null, 3, 0,
                    "[]", "[]", "{}", "QUEUED", "request", null, null,
                    java.time.Instant.now(), java.time.Instant.now(), "SSH", 2L, 3L,
                    "gputrain-00000000-0000-0000-0000-000000000000.service", "/data/train"))
            .getSql().replaceAll("\\s+", " ");
        assertTrue(insertSql.contains("execution_mode, server_id, account_id, remote_unit_name, remote_working_directory"));

        String leaseResource = "mapper/train/SshGpuLeaseMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(leaseResource))
        {
            assertNotNull(input, leaseResource);
            new XMLMapperBuilder(input, configuration, leaseResource, configuration.getSqlFragments()).parse();
        }
        for (var method : SshGpuLeaseMapper.class.getDeclaredMethods())
            assertTrue(configuration.hasStatement(SshGpuLeaseMapper.class.getName() + "." + method.getName()), method.toString());
        var leaseMap = configuration.getResultMap(SshGpuLeaseMapper.class.getName() + ".LeaseResult");
        Class<?>[] leaseTypes = leaseMap.getConstructorResultMappings().stream()
            .map(org.apache.ibatis.mapping.ResultMapping::getJavaType).toArray(Class<?>[]::new);
        assertDoesNotThrow(() -> SshGpuLeaseRecord.class.getDeclaredConstructor(leaseTypes));
        for (String select : List.of("findByJob", "findActiveByServerGpu"))
            assertFalse(configuration.getMappedStatement(SshGpuLeaseMapper.class.getName() + "." + select)
                .getBoundSql(java.util.Map.of("jobId", 1L, "serverId", 1L, "gpuUuid", "GPU-1"))
                .getSql().contains("*"));
    }
}
