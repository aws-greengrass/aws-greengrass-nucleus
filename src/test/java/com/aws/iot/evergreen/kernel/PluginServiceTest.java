package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

public class PluginServiceTest extends EGServiceTestUtil {

    private PluginService pluginService;

    @BeforeEach
    void beforeEach() {
        pluginService = new PluginService(initializeMockedConfig());
    }

    @Test
    void GIVEN_new_config_and_current_version_unknown_WHEN_isBootstrapRequired_THEN_return_false() {
        doReturn(null).when(config).find(eq(VERSION_CONFIG_KEY));
        assertTrue(pluginService.isBootstrapRequired(Collections.emptyMap()));
    }

    @Test
    void GIVEN_new_config_with_new_version_WHEN_isBootstrapRequired_THEN_return_true() {
        doReturn(Topic.of(context, VERSION_CONFIG_KEY, "1.0.0")).when(config).find(eq(VERSION_CONFIG_KEY));
        assertTrue(pluginService.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.1");
        }}));
    }

    @Test
    void GIVEN_new_config_with_old_version_WHEN_isBootstrapRequired_THEN_return_true() {
        doReturn(Topic.of(context, VERSION_CONFIG_KEY, "1.0.0")).when(config).find(eq(VERSION_CONFIG_KEY));
        assertFalse(pluginService.isBootstrapRequired(new HashMap<String, Object>() {{
            put(VERSION_CONFIG_KEY, "1.0.0");
        }}));
    }
}
