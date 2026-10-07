package com.ruoyi.train.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.train.agent.contract.PlannerInput;

class AgentControllerTest
{
    @Test
    void planAndDiagnosisRequireViewPermission() throws Exception
    {
        assertPermission("plan", PlannerInput.class, "train:job:view");
        assertPermission("diagnose", AgentController.DiagnoseRequest.class, "train:job:view");
    }

    @Test
    void executionDraftRequiresManagePermission() throws Exception
    { assertPermission("executionDraft", AgentController.ExecutionDraftRequest.class, "train:job:manage"); }

    private void assertPermission(String methodName, Class<?> argument, String permission) throws Exception
    {
        Method method = AgentController.class.getMethod(methodName, argument);
        PreAuthorize auth = method.getAnnotation(PreAuthorize.class);
        assertNotNull(auth);
        assertEquals("@ss.hasPermi('" + permission + "')", auth.value());
    }
}
