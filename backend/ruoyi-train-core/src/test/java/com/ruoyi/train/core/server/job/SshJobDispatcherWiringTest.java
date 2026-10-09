package com.ruoyi.train.core.server.job;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.ruoyi.train.core.job.service.JobService;
import com.ruoyi.train.core.server.service.ServerDeviceQueryService;
import com.ruoyi.train.core.server.service.ServerInventoryService;
import com.ruoyi.train.core.server.service.ServerProbeService;
import com.ruoyi.train.core.server.ssh.MinaSshGateway;
import com.ruoyi.train.core.server.ssh.ServerSecretCipher;

class SshJobDispatcherWiringTest
{
    @Test
    void springManagedComponentsWithMultipleConstructorsDeclareInjectionConstructor()
    {
        List<Class<?>> components = List.of(SshJobDispatcher.class, SshJobReconciler.class,
                SshLogCollector.class, SshRemoteJobClient.class, MinaSshGateway.class, ServerSecretCipher.class,
                JobService.class, ServerDeviceQueryService.class, ServerInventoryService.class,
                ServerProbeService.class);
        for (Class<?> component : components)
        {
            long injectionConstructors = Arrays.stream(component.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class)).count();
            assertEquals(1, injectionConstructors, component.getSimpleName());
        }
    }
}
