/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static com.aws.iot.evergreen.kernel.EvergreenService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.Lifecycle.STATE_TOPIC_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class EvergreenServiceTest extends EGServiceTestUtil {

    @Mock
    private Kernel kernel;
    private Context context;
    private Configuration configuration;
    private EvergreenService aService;
    private EvergreenService bService;
    private EvergreenService cService;
    private EvergreenService dService;
    private EvergreenService eService;

    @BeforeEach
    void beforeEach() throws IOException, URISyntaxException, ServiceLoadException {
        Path configPath = Paths.get(this.getClass().getResource("services.yaml").toURI());
        context = spy(new Context());
        when(context.get(Kernel.class)).thenReturn(kernel);
        configuration = new Configuration(context);
        configuration.read(configPath);
        Topics root = configuration.getRoot();
        bService = new EvergreenService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "B"));
        cService = new EvergreenService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "C"));
        dService = new EvergreenService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "D"));
        eService = new EvergreenService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "E"));
        when(kernel.locate("B")).thenReturn(bService);
        when(kernel.locate("C")).thenReturn(cService);
        when(kernel.locate("D")).thenReturn(dService);
        lenient().when(kernel.locate("E")).thenReturn(eService);
        aService = spy(new EvergreenService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "A")));

    }

    @AfterEach
    void afterEach() throws IOException {
        context.close();
    }

    @Test
    void GIVEN_a_config_WHEN_constructor_is_called_THEN_service_is_initialized() {
        // GIVEN
        // provided in the beforeEach

        // WHEN
        // provided in the beforeEach

        // THEN
        // verify config
        Assertions.assertSame(configuration.getRoot().findTopics(SERVICES_NAMESPACE_TOPIC, "A"), aService.config);

        //verify dependencies are set up right
        assertEquals(aService.dependencies.size(), 3);
        assertEquals(aService.dependencies.get(bService).dependencyType, DependencyType.HARD);
        assertEquals(aService.dependencies.get(cService).dependencyType, DependencyType.SOFT);
        assertEquals(aService.dependencies.get(dService).dependencyType, DependencyType.HARD);

        //verify state is NEW
        Topic stateTopic = aService.getConfig().find(PRIVATE_STORE_NAMESPACE_TOPIC, STATE_TOPIC_NAME);
        assertEquals(stateTopic.getOnce(), State.NEW);
        assertFalse(stateTopic.parentNeedsToKnow());

    }

    @Test
    void GIVEN_service_WHEN_dependencies_change_THEN_service_restarts() {
        //GIVEN service A with dependencies B,C,D
        // provided in the beforeEach
      
        //WHEN D is removed and E is added
        Topic topic = aService.getConfig().find(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        topic.withNewerValue(System.currentTimeMillis(), Arrays.asList("B", "C", "E"));
        context.runOnPublishQueueAndWait(() -> {
        });
        // THEN
        // verify D is removed and E is added
        assertEquals(aService.dependencies.size(), 3);
        assertNull(aService.dependencies.get(dService));
        assertNotNull(aService.dependencies.get(eService));
    }
}
