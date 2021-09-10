/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.provisioning;

import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.IotJobsHelper;
import com.aws.greengrass.deployment.ShadowDeploymentListener;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelLifecycle;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.crt.mqtt.MqttException;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(GGExtension.class)
public class ProvisionFromTestPlugin extends BaseITCase {

    private Kernel kernel;

    @TempDir
    Path configurationFilePath;
    @TempDir
    Path certFilePath;
    @TempDir
    Path privateKeyPath;
    @TempDir
    Path rootCAPath;

    @BeforeEach
    void before() {
        kernel = new Kernel();
        mockRunasExePath();
    }

    @AfterEach
    @SuppressWarnings("PMD.DoNotCallGarbageCollectionExplicitly")
    void after() throws IOException {
        if (kernel != null) {
            // Executor service is not able to terminate threads in some tests within the default 30 seconds
            kernel.shutdown(40);
            deleteProvisioningPlugins();
        }
    }

    @Order(1)
    @Test
    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    void GIVEN_nucleus_started_with_testProvisioningPlugin_AND_without_config_params_THEN_device_runs_offline_mode() throws Throwable {
        URL filepath = getClass().getResource("config_without_test_provisioning_plugin.yaml");
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, filepath);
        CountDownLatch logLatch =  new CountDownLatch(2);
        CountDownLatch reverseLatch = new CountDownLatch(1);
        DeviceConfiguration deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
        try (AutoCloseable listener = TestUtils.createCloseableLogListener((message) -> {
            String messageString = message.getMessage();
            if (messageString.contains(IotJobsHelper.DEVICE_OFFLINE_MESSAGE)
                    || messageString.contains(ShadowDeploymentListener.DEVICE_OFFLINE_MESSAGE)) {
                logLatch.countDown();
            } else if (KernelLifecycle.UPDATED_PROVISIONING_MESSAGE.equals(messageString)) {
                deviceConfiguration.onAnyChange((what, node) -> {
                    if (node != null && WhatHappened.childChanged.equals(what)) {
                        if (node.childOf(DEVICE_PARAM_THING_NAME) || node.childOf(DEVICE_PARAM_IOT_DATA_ENDPOINT)
                                || node.childOf(DEVICE_PARAM_PRIVATE_KEY_PATH)
                                || node.childOf(DEVICE_PARAM_CERTIFICATE_FILE_PATH) || node.childOf(DEVICE_PARAM_ROOT_CA_PATH)
                                || node.childOf(DEVICE_PARAM_AWS_REGION)) {
                            reverseLatch.countDown();
                        }
                    }
                });
                logLatch.countDown();
            }
        })) {
            kernel.launch();
            assertTrue(logLatch.await(2, TimeUnit.SECONDS));
            assertFalse(reverseLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Order(2)
    @Test
    void GIVEN_nucleus_started_with_testProvisioningPlugin_AND_plugin_throws_non_retryable_exception_THEN_device_runs_in_offline_mode(ExtensionContext context) throws Throwable {
        ignoreExceptionUltimateCauseOfType(context, NumberFormatException.class);
        URL filepath = getClass().getResource("config_with_test_provisioning_plugin_template.yaml");
        String configBody = new String(Files.readAllBytes(Paths.get(filepath.toURI())), StandardCharsets.UTF_8);
        String generatedCertFilePath = Files.createTempFile(certFilePath, null, ".pem")
                .toAbsolutePath().toString();
        configBody = replaceConfigParameters(configBody, generatedCertFilePath, "InvalidNumber");
        Path configFilePath = Paths.get(String.valueOf(configurationFilePath), "config.yaml");
        Files.write(configFilePath, configBody.getBytes(StandardCharsets.UTF_8));
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, configFilePath.toUri().toURL());

        CountDownLatch logLatch =  new CountDownLatch(1);
        CountDownLatch reverseLatch = new CountDownLatch(1);
        try (AutoCloseable listener = TestUtils.createCloseableLogListener((message) -> {
                String messageString = message.getMessage();
                if (messageString.contains(IotJobsHelper.DEVICE_OFFLINE_MESSAGE)) {
                    logLatch.countDown();
                } });
                AutoCloseable reverseListener = TestUtils.createCloseableLogListener((message) -> {
                 String messageString = message.getMessage();
                 if (messageString.contains(KernelLifecycle.UPDATED_PROVISIONING_MESSAGE)) {
                     reverseLatch.countDown();
                 } })
            ) {
            kernel.launch();
            assertTrue(logLatch.await(2, TimeUnit.SECONDS));
            assertFalse(reverseLatch.await(2, TimeUnit.SECONDS));
        }
    }

    @Order(3)
    @Test
    void GIVEN_nucleus_started_with_multiple_provisioningPlugins_THEN_nucleus_launch_fails(ExtensionContext context) throws Throwable {
        ignoreExceptionUltimateCauseOfType(context, MqttException.class);
        ignoreExceptionUltimateCauseOfType(context, InvalidKeyException.class);
        URL filepath = getClass().getResource("config_with_test_provisioning_plugin_template.yaml");
        String configBody = new String(Files.readAllBytes(Paths.get(filepath.toURI())), StandardCharsets.UTF_8);
        String generatedCertFilePath = Files.createTempFile(certFilePath, null, ".pem")
                .toAbsolutePath().toString();
        configBody = replaceConfigParameters(configBody, generatedCertFilePath, "5000");
        Path configFilePath = Paths.get(String.valueOf(configurationFilePath), "config.yaml");
        Files.write(configFilePath, configBody.getBytes(StandardCharsets.UTF_8));

        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, configFilePath.toUri().toURL());
        addProvisioningPlugin("testProvisioningPlugin-tests.jar");
        addProvisioningPlugin("additionalDeviceProvisioningPlugin-tests.jar");

        Exception e = assertThrows(RuntimeException.class, () -> kernel.launch());
        assertThat(e.getMessage(), containsString("Multiple provisioning plugins found"));
    }

    @Order(4)
    @Test
    void GIVEN_plugin_jar_added_to_trusted_plugins_dir_AND_plugin_takes_time_to_run_THEN_device_runs_offline_then_comes_online(ExtensionContext context) throws Throwable {
        ignoreExceptionUltimateCauseOfType(context, MqttException.class);
        ignoreExceptionUltimateCauseOfType(context, CrtRuntimeException.class);
        ignoreExceptionUltimateCauseOfType(context, InvalidKeyException.class);
        URL filepath = getClass().getResource("config_with_test_provisioning_plugin_template.yaml");
        String configBody = new String(Files.readAllBytes(Paths.get(filepath.toURI())), StandardCharsets.UTF_8);
        String generatedCertFilePath = Files.createTempFile(certFilePath, null, ".pem.crt")
                .toAbsolutePath().toString();
        configBody = replaceConfigParameters(configBody, generatedCertFilePath, "5000");
        Path configFilePath = Paths.get(String.valueOf(configurationFilePath), "config.yaml");
        Files.write(configFilePath, configBody.getBytes(StandardCharsets.UTF_8));

        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, configFilePath.toUri().toURL());
        addProvisioningPlugin("testProvisioningPlugin-tests.jar");
        CountDownLatch logLatch =  new CountDownLatch(5);
        try (AutoCloseable listener = TestUtils.createCloseableLogListener((message) -> {
            String messageString = message.getMessage();
            if (messageString.contains(IotJobsHelper.DEVICE_OFFLINE_MESSAGE)
                    || logLatch.getCount() < 5 && KernelLifecycle.UPDATED_PROVISIONING_MESSAGE.equals(messageString)
                    || IotJobsHelper.SUBSCRIBING_TO_TOPICS_MESSAGE.equals(messageString) && logLatch.getCount() < 4
                    || ShadowDeploymentListener.SUBSCRIBING_TO_SHADOW_TOPICS_MESSAGE
                        .equals(messageString) && logLatch.getCount() < 4
                    || GreengrassServiceClientFactory.CONFIGURING_GGV2_INFO_MESSAGE
                        .equals(messageString) && logLatch.getCount() < 4) {
                logLatch.countDown();
            }
        })) {
            kernel.launch();
            assertTrue(logLatch.await(7, TimeUnit.SECONDS));
            kernel.getContext().waitForPublishQueueToClear();
            DeviceConfiguration deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
            assertEquals("test.us-east-1.iot.data.endpoint", Coerce.toString(deviceConfiguration.getIotDataEndpoint()));
            assertEquals(generatedCertFilePath, Coerce.toString(deviceConfiguration.getCertificateFilePath()));
        }
    }

    @Order(5)
    @Test
    void GIVEN_plugin_jar_added_to_trusted_plugins_dir_AND_plugin_return_immediately_THEN_device_comes_online(ExtensionContext context) throws Throwable {
        ignoreExceptionUltimateCauseOfType(context, MqttException.class);
        ignoreExceptionUltimateCauseOfType(context, CrtRuntimeException.class);
        ignoreExceptionUltimateCauseOfType(context, InvalidKeyException.class);
        URL filepath = getClass().getResource("config_with_test_provisioning_plugin_template.yaml");
        String configBody = new String(Files.readAllBytes(Paths.get(filepath.toURI())), StandardCharsets.UTF_8);
        String generatedCertFilePath = Files.createTempFile(certFilePath, null, ".pem.crt")
                .toAbsolutePath().toString();
        configBody = replaceConfigParameters(configBody, generatedCertFilePath, "0");
        Path configFilePath = Paths.get(String.valueOf(configurationFilePath), "config.yaml");
        Files.write(configFilePath, configBody.getBytes(StandardCharsets.UTF_8));

        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, configFilePath.toUri().toURL());
        addProvisioningPlugin("testProvisioningPlugin-tests.jar");
        CountDownLatch logLatch =  new CountDownLatch(4);
        try ( AutoCloseable listener = TestUtils.createCloseableLogListener((message) -> {
            String messageString = message.getMessage();
            if (KernelLifecycle.UPDATED_PROVISIONING_MESSAGE.equals(messageString)
                    || IotJobsHelper.SUBSCRIBING_TO_TOPICS_MESSAGE.equals(messageString) && logLatch.getCount() < 4
                    || ShadowDeploymentListener.SUBSCRIBING_TO_SHADOW_TOPICS_MESSAGE
                        .equals(messageString) && logLatch.getCount() < 4
                    || GreengrassServiceClientFactory.CONFIGURING_GGV2_INFO_MESSAGE
                        .equals(messageString) && logLatch.getCount() < 4) {
                logLatch.countDown();
            }
        })) {
            kernel.launch();
            assertTrue(logLatch.await(7, TimeUnit.SECONDS));
            kernel.getContext().waitForPublishQueueToClear();
            DeviceConfiguration deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
            assertEquals("test.us-east-1.iot.data.endpoint", Coerce.toString(deviceConfiguration.getIotDataEndpoint()));
            assertEquals(generatedCertFilePath, Coerce.toString(deviceConfiguration.getCertificateFilePath()));
        }
    }

    private String replaceConfigParameters(String configTemplate, String generatedCertFilePath, String waitTimeInMS) throws IOException {
        configTemplate = configTemplate.replace("$certfilepath", generatedCertFilePath);
        configTemplate = configTemplate.replace("$privatekeypath", Files.createTempFile(privateKeyPath, null,
                ".pem.key")
                .toAbsolutePath().toString());
        configTemplate = configTemplate.replace("$rootcapath", Files.createTempFile(rootCAPath, null,
                ".pem.crt")
                .toAbsolutePath().toString());
        configTemplate = configTemplate.replace("$waittimems", waitTimeInMS);
        return configTemplate;
    }

    private void addProvisioningPlugin(String jarName) throws IOException {
        Path trustedPluginsDir = kernel.getNucleusPaths().pluginPath().resolve("trusted");
        Path testPluginJarPath = new File(getClass().getResource(jarName).getFile()).toPath();
        Files.createDirectories(trustedPluginsDir);
        Files.copy(testPluginJarPath, trustedPluginsDir.resolve(Utils.namePart(testPluginJarPath.toString())));
    }

    private void deleteProvisioningPlugins() throws IOException {
        Utils.deleteFileRecursively(kernel.getNucleusPaths().pluginPath().resolve("trusted").toFile());
    }
}
