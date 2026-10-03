package com.ruoyi.train.core.runner.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import com.ruoyi.common.annotation.Anonymous;

class RunnerControllerSecurityTest
{
    @Test
    void enrollmentEndpointRequiresRunnerEnrollPermission()
    {
        assertEquals("/train/runners/enrollments", RunnerAdminController.class
                .getAnnotation(RequestMapping.class).value()[0]);
        Method method = method(RunnerAdminController.class, "createEnrollment");
        assertEquals("@ss.hasPermi('train:runner:enroll')", method.getAnnotation(PreAuthorize.class).value());
    }

    @Test
    void registrationEndpointIsAnonymous()
    {
        assertEquals("/runner-api/v1/register", RunnerRegistrationController.class
                .getAnnotation(RequestMapping.class).value()[0]);
        assertNotNull(method(RunnerRegistrationController.class, "register").getAnnotation(Anonymous.class));
    }

    private static Method method(Class<?> type, String name)
    {
        return java.util.Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
    }
}
