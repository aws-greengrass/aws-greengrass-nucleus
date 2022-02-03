/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.lifecyclemanager.UpdateSystemPolicyService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.STATE_TOPIC_NAME;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class GGServiceTestUtil {

    protected String serviceFullName = "GreengrassServiceFullName";

    @Mock
    protected UpdateSystemPolicyService updateSystemPolicyService;

    @Mock(lenient = true)
    protected Topics config;

    @Mock
    protected Topic stateTopic;

    @Mock
    protected Topic dependenciesTopic;

    @Mock
    protected Topics runtimeStoreTopic;

    @Mock
    protected Topics privateStoreTopic;

    @Mock
    protected Context context;

    @Mock
    private KernelAlternatives kernelAlternatives;

    public Topics initializeMockedConfig() {
        lenient().when(config.lookupTopics(eq(RUNTIME_STORE_NAMESPACE_TOPIC), anyString(), anyString()))
                .thenReturn(runtimeStoreTopic);
        lenient().when(config.lookupTopics(eq(PRIVATE_STORE_NAMESPACE_TOPIC), anyString(), anyString()))
                .thenReturn(privateStoreTopic);
        lenient().when(config.lookupTopics(eq(RUNTIME_STORE_NAMESPACE_TOPIC))).thenReturn(runtimeStoreTopic);
        lenient().when(config.lookupTopics(eq(PRIVATE_STORE_NAMESPACE_TOPIC))).thenReturn(privateStoreTopic);
        lenient().when(privateStoreTopic.createLeafChild(eq(STATE_TOPIC_NAME))).thenReturn(stateTopic);
        lenient().when(config.createLeafChild(eq(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC))).thenReturn(dependenciesTopic);
        lenient().when(config.getName()).thenReturn(serviceFullName);
        lenient().when(dependenciesTopic.dflt(Mockito.any())).thenReturn(dependenciesTopic);
        lenient().when(dependenciesTopic.getOnce()).thenReturn(new ArrayList<>());
        lenient().when(config.getContext()).thenReturn(context);
        lenient().when(context.get(ExecutorService.class)).thenReturn(mock(ExecutorService.class));
        lenient().when(context.get(Kernel.class)).thenReturn(mock(Kernel.class));
        lenient().when(context.get(eq(UpdateSystemPolicyService.class))).thenReturn(updateSystemPolicyService);
        lenient().when(context.get(KernelAlternatives.class)).thenReturn(kernelAlternatives);
        return config;
    }
}
