package com.ruoyi.train.core.server.proposal;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ruoyi.train.core.job.service.JobService;

/**
 * 提案动作集装配：7 个零参只读探测（渲染 MinaSshGateway.COMMANDS 固定 argv）
 * + 2 个训练任务控制动作（inspect/stop）。每个动作单独注册为 Bean，
 * 由 {@link CommandActionRegistry} 收集去重。
 */
@Configuration
public class CommandActionConfig
{
    @Bean
    public CommandAction probeLoadavg()
    {
        return CommandAction.fixed("probe-loadavg", "查看远端系统负载（1/5/15 分钟）", List.of("cat", "/proc/loadavg"));
    }

    @Bean
    public CommandAction probeMeminfo()
    {
        return CommandAction.fixed("probe-meminfo", "查看远端内存信息", List.of("cat", "/proc/meminfo"));
    }

    @Bean
    public CommandAction probeDisk()
    {
        return CommandAction.fixed("probe-disk", "查看远端磁盘使用（POSIX 1024 字节块）", List.of("df", "-Pk"));
    }

    @Bean
    public CommandAction probeGpu()
    {
        return CommandAction.fixed("probe-gpu", "查询 GPU 概要（名称/显存/利用率/MIG/驱动）",
            List.of("nvidia-smi", "--query-gpu=gpu_uuid,name,index,memory.total,memory.used,utilization.gpu,mig.mode.current,driver_version",
                "--format=csv,noheader,nounits"));
    }

    @Bean
    public CommandAction probeGpuProcesses()
    {
        return CommandAction.fixed("probe-gpu-processes", "查询 GPU 计算进程（UUID/PID/进程名/显存）",
            List.of("nvidia-smi", "--query-compute-apps=gpu_uuid,pid,process_name,used_memory",
                "--format=csv,noheader,nounits"));
    }

    @Bean
    public CommandAction probeSystemd()
    {
        return CommandAction.fixed("probe-systemd", "检查用户 systemd 实例运行状态",
            List.of("systemctl", "--user", "is-system-running"));
    }

    @Bean
    public CommandAction probeCapability()
    {
        return CommandAction.fixed("probe-capability", "验证账号 systemd-run 能力（exitCode=0 即具备）",
            List.of("systemd-run", "--user", "--wait", "--collect", "--unit=gputrain-capability-probe.service",
                "--property=StandardOutput=append:/dev/null", "--property=StandardError=append:/dev/null",
                "/usr/bin/true"));
    }

    @Bean
    public CommandAction jobInspect(JobService jobs)
    {
        return new JobAction(JobAction.Kind.INSPECT, jobs);
    }

    @Bean
    public CommandAction jobStop(JobService jobs)
    {
        return new JobAction(JobAction.Kind.STOP, jobs);
    }
}
