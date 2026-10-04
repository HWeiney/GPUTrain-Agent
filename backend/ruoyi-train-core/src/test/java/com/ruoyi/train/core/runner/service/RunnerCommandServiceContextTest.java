package com.ruoyi.train.core.runner.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.train.core.runner.mapper.RunnerCommandMapper;

class RunnerCommandServiceContextTest
{
    @Test
    void springCreatesCommandServiceWithProductionCollaborators()
    {
        RunnerCommandMapper mapper = (RunnerCommandMapper) Proxy.newProxyInstance(
                RunnerCommandMapper.class.getClassLoader(), new Class<?>[] { RunnerCommandMapper.class },
                (proxy, method, args) -> null);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext())
        {
            context.registerBean(RunnerCommandMapper.class, () -> mapper);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(RunnerCommandService.class);
            context.refresh();
            assertNotNull(context.getBean(RunnerCommandService.class));
        }
    }
}
