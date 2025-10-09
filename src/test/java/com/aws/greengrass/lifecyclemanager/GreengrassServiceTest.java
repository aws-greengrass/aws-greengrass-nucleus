/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith({
        GGExtension.class, MockitoExtension.class
})
class GreengrassServiceTest {

    @Mock
    private Kernel kernel;
    private Context context;
    private Configuration configuration;
    private ExecutorService executorService;
    private GreengrassService aService;
    private GreengrassService bService;
    private GreengrassService cService;
    private GreengrassService dService;
    private GreengrassService eService;

    @BeforeEach
    void beforeEach() throws IOException, URISyntaxException, ServiceLoadException {
        Path configPath = Paths.get(this.getClass().getResource("services.yaml").toURI());
        context = spy(new Context());
        context.put(Kernel.class, kernel);
        executorService = Executors.newFixedThreadPool(1);
        context.put(Executor.class, executorService);
        configuration = new Configuration(context);
        configuration.read(configPath);
        Topics root = configuration.getRoot();
        bService = new GreengrassService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "B"));
        cService = new GreengrassService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "C"));
        dService = new GreengrassService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "D"));
        eService = new GreengrassService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "E"));
        when(kernel.locateIgnoreError("B")).thenReturn(bService);
        when(kernel.locateIgnoreError("C")).thenReturn(cService);
        when(kernel.locateIgnoreError("D")).thenReturn(dService);
        lenient().when(kernel.locateIgnoreError("E")).thenReturn(eService);
        aService = spy(new GreengrassService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "A")));
    }

    @AfterEach
    void afterEach() throws IOException {
        context.close();
        executorService.shutdownNow();
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

        // verify dependencies are set up right
        assertEquals(3, aService.dependencies.size());
        assertEquals(DependencyType.HARD, aService.dependencies.get(bService).dependencyType);
        assertEquals(DependencyType.SOFT, aService.dependencies.get(cService).dependencyType);
        assertEquals(DependencyType.HARD, aService.dependencies.get(dService).dependencyType);

        // verify state is NEW
        Topic stateTopic = aService.getConfig().find(PRIVATE_STORE_NAMESPACE_TOPIC, Lifecycle.STATE_TOPIC_NAME);
        assertEquals(State.NEW, Coerce.toEnum(State.class, stateTopic));
        assertFalse(stateTopic.parentNeedsToKnow());
    }

    @Test
    void GIVEN_service_WHEN_dependencies_change_THEN_service_dependencies_updates() {
        // GIVEN service A with dependencies B,C,D
        // provided in the beforeEach

        // WHEN D is removed and E is added
        Topic topic = aService.getConfig().find(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        topic.withValue(Arrays.asList("B", "C", "E"));
        context.runOnPublishQueueAndWait(() -> {
        });
        // THEN
        // verify D is removed and E is added
        assertEquals(3, aService.dependencies.size());
        assertNull(aService.dependencies.get(dService));
        assertNotNull(aService.dependencies.get(eService));
    }

    @Test
    void GIVEN_service_WHEN_service_is_closed_THEN_service_dependency_watcher_is_removed() throws Exception {
        // GIVEN service
        Path configPath = Paths.get(this.getClass().getResource("services.yaml").toURI());
        Configuration serviceConfiguration = new Configuration(context);
        serviceConfiguration.read(configPath);
        Topics root = serviceConfiguration.getRoot();
        Topics serviceTopics = spy(root.findTopics(SERVICES_NAMESPACE_TOPIC, "B"));
        Topic externalDependencyTopic = mock(Topic.class);

        doReturn(context).when(serviceTopics).getContext();
        doReturn(externalDependencyTopic).when(serviceTopics).createLeafChild(eq(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC));
        doReturn(externalDependencyTopic).when(externalDependencyTopic).dflt(any());
        doReturn(Collections.emptyList()).when(externalDependencyTopic).getOnce();

        GreengrassService service = spy(new GreengrassService(serviceTopics));

        // WHEN
        AtomicBoolean watcherRemoved = new AtomicBoolean(false);
        doAnswer((Answer<Void>) invocationOnMock -> {
            watcherRemoved.set(true);
            return null;
        }).when(externalDependencyTopic).remove(any());

        service.close().get(30, TimeUnit.SECONDS);

        // THEN
        assertTrue(watcherRemoved.get());
    }
}
