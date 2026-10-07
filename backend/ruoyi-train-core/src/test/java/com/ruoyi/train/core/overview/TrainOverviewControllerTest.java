package com.ruoyi.train.core.overview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import com.ruoyi.common.core.domain.AjaxResult;

class TrainOverviewControllerTest
{
    @Test
    void controllerReturnsOverviewAsAjaxResultData()
    {
        TrainOverviewController controller = new TrainOverviewController(new TrainOverviewService(null, null, null) {
            @Override public TrainOverview getOverview() { return new TrainOverview("READY", 2, 1); }
        });

        AjaxResult response = controller.overview();

        assertEquals(200, response.get("code"));
        assertInstanceOf(TrainOverview.class, response.get("data"));
    }

    @Test
    void overviewRequiresViewPermission() throws NoSuchMethodException
    {
        Method endpoint = TrainOverviewController.class.getMethod("overview");

        PreAuthorize authorization = endpoint.getAnnotation(PreAuthorize.class);

        assertNotNull(authorization);
        assertEquals("@ss.hasPermi('train:overview:view')", authorization.value());
    }
}
