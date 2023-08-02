/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelCommandLine;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static com.aws.greengrass.integrationtests.lifecyclemanager.PluginComponentTest.componentId;
import static com.aws.greengrass.integrationtests.lifecyclemanager.PluginComponentTest.setDigestInConfig;
import static com.aws.greengrass.integrationtests.lifecyclemanager.PluginComponentTest.setupPackageStore;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessageSubstring;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class UnloadableServiceIntegTest extends BaseITCase {

    private Kernel kernel;

    @BeforeEach
    void beforeEach() {
        kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);
    }

    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_unloadable_service_missing_config_WHEN_nucleus_launch_THEN_nucleus_starts_and_other_services_running(
            ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "No matching definition in system model for: MissingConfigService");
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("unloadable_service_missing_config.yaml"));
        kernel.launch();

        assertThat(kernel.locate("ServiceA")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("MissingConfigService")::getState, eventuallyEval(is(State.BROKEN)));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.INSTALLED)));
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.INSTALLED)));
        assertEquals(4, kernel.orderedDependencies().stream().filter(s -> !s.isBuiltin()).count());
    }

    @Test
    void GIVEN_unloadable_plugin_missing_jar_WHEN_nucleus_launch_THEN_nucleus_starts_and_other_services_running(
            ExtensionContext context) throws Exception {
        ignoreExceptionWithMessageSubstring(context, "Unable to find plugin");
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("unloadable_plugin.yaml"));
        kernel.launch();

        Duration eventually = Duration.ofSeconds(20);
        assertThat(kernel.locate("ServiceA")::getState, eventuallyEval(is(State.RUNNING), eventually));
        assertThat(kernel.locate("ServiceB")::getState, eventuallyEval(is(State.RUNNING), eventually));
        assertThat(kernel.locate("ServiceC")::getState, eventuallyEval(is(State.RUNNING), eventually));
        assertThat(kernel.locate("plugin")::getState, eventuallyEval(is(State.BROKEN), eventually));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.INSTALLED), eventually));
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.INSTALLED), eventually));
        assertEquals(6, kernel.orderedDependencies().stream().filter(s -> !s.isBuiltin()).count());

        // Fix plugin by adding jar to component store and restart Nucleus
        setupPackageStore(kernel, componentId);
        setDigestInConfig(kernel);
        kernel.shutdown();
        kernel = new Kernel().parseArgs();
        kernel.launch();

        assertThat(kernel.locate("ServiceA")::getState, eventuallyEval(is(State.RUNNING), eventually));
        assertThat(kernel.locate("ServiceB")::getState, eventuallyEval(is(State.RUNNING), eventually));
        assertThat(kernel.locate("ServiceC")::getState, eventuallyEval(is(State.RUNNING), eventually));
        assertThat(kernel.locate("plugin")::getState, eventuallyEval(is(State.RUNNING), eventually));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.RUNNING), eventually));
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED), eventually));
    }

    @Test
    void GIVEN_unloadable_plugin_digest_missing_WHEN_nucleus_launch_THEN_nucleus_starts_and_other_services_running(
            ExtensionContext context) throws Exception {
        ignoreExceptionWithMessageSubstring(context, "Locally deployed plugin components are not supported");
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("unloadable_plugin.yaml"));
        setupPackageStore(kernel, componentId);
        kernel.launch();

        assertThat(kernel.locate("ServiceA")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("ServiceB")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("ServiceC")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("plugin")::getState, eventuallyEval(is(State.BROKEN)));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.INSTALLED)));
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.INSTALLED)));
        assertEquals(6, kernel.orderedDependencies().stream().filter(s -> !s.isBuiltin()).count());

        // Fix plugin by adding digest in config and restart Nucleus
        setDigestInConfig(kernel);
        kernel.shutdown();
        kernel = new Kernel().parseArgs();
        kernel.launch();

        assertThat(kernel.locate("ServiceA")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("ServiceB")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("ServiceC")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("plugin")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
    }

    @Test
    void GIVEN_unloadable_plugin_digest_mismatch_WHEN_nucleus_launch_THEN_nucleus_starts_and_other_services_running(
            ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "Plugin recipe has been modified after it was downloaded");
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("unloadable_plugin.yaml"));
        setupPackageStore(kernel, componentId);
        kernel.getConfig()
                .lookupTopics(GreengrassService.SERVICES_NAMESPACE_TOPIC,
                        KernelCommandLine.MAIN_SERVICE_NAME,
                        GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC)
                .lookup(Kernel.SERVICE_DIGEST_TOPIC_KEY, componentId.toString())
                .withValue("wrong-digest");

        kernel.launch();

        assertThat(kernel.locate("ServiceA")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("ServiceB")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("ServiceC")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("plugin")::getState, eventuallyEval(is(State.BROKEN)));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.INSTALLED)));
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.INSTALLED)));
        assertEquals(6, kernel.orderedDependencies().stream().filter(s -> !s.isBuiltin()).count());

        // Fix plugin by correcting digest in config and restart Nucleus
        setDigestInConfig(kernel);
        kernel.shutdown();
        kernel = new Kernel().parseArgs();
        kernel.launch();

        assertThat(kernel.locate("ServiceA")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("ServiceB")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("ServiceC")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("plugin")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
    }
}
