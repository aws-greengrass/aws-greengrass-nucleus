/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.provisioning;

import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.crt.mqtt.MqttException;

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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
public class ProvisionFromTestPlugin extends BaseITCase {
    private Kernel kernel;
    private static SocketOptions socketOptions;

    @TempDir
    Path configurationFilePath;
    @TempDir
    Path certFilePath;
    @TempDir
    Path privateKeyPath;
    @TempDir
    Path rootCAPath;

    @BeforeAll
    static void initialize() {
        socketOptions = TestUtils.getSocketOptionsForIPC();
    }

    @BeforeEach
    void before() {
        kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @AfterAll
    static void tearDown() {
        if (socketOptions != null) {
            socketOptions.close();
        }
    }


    @Test
    void GIVEN_nucleus_started_with_testProvisioningPlugin_THEN_configuration_updated_AND_listeners_invoked(ExtensionContext context) throws Throwable {
        ignoreExceptionUltimateCauseOfType(context, MqttException.class);
        ignoreExceptionUltimateCauseOfType(context, InvalidKeyException.class);
        URL filepath = getClass().getResource("config_with_test_provisioning_plugin.yaml");
        String configBody = new String(Files.readAllBytes(Paths.get(filepath.toURI())), StandardCharsets.UTF_8);
        String generatedCertFilePath = Files.createTempFile(certFilePath, null, ".pem")
                .toAbsolutePath().toString();
        configBody = replaceConfgPrameters(configBody, generatedCertFilePath, "5000");
        Path configFilePath = Paths.get(String.valueOf(configurationFilePath), "config.yaml");
        Files.write(configFilePath, configBody.getBytes(StandardCharsets.UTF_8));

        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, configFilePath.toUri().toURL());
        CountDownLatch logLatch =  new CountDownLatch(5);
        TestUtils.createCloseableLogListener((message) -> {
            String messageString = message.getMessage();
            if (messageString.contains("Device not configured to talk to AWS Iot cloud")
                || logLatch.getCount() < 5 && "Updated provisioning configuration".equals(messageString)
                    || "Subscribing to Iot Jobs Topics".equals(messageString) && logLatch.getCount() < 4
                    || "Subscribing to Iot Shadow topics".equals(messageString) && logLatch.getCount() < 4
                    || "Configuring GGV2 client".equals(messageString) && logLatch.getCount() < 4) {
                logLatch.countDown();
            }
        });

        kernel.launch();
        assertTrue(logLatch.await(7, TimeUnit.SECONDS));
        DeviceConfiguration deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
        assertEquals("test.us-east-1.iot.data.endpoint", Coerce.toString(deviceConfiguration.getIotDataEndpoint()));
        assertEquals(generatedCertFilePath, Coerce.toString(deviceConfiguration.getCertificateFilePath()));
    }


    @Test
    @SuppressWarnings("PMD.AvoidDeeplyNestedIfStmts")
    void GIVEN_nucleus_started_with_testProvisioningPlugin_AND_without_config_params_THEN_device_runs_offline_mode() throws Throwable {
        URL filepath = getClass().getResource("config_without_test_provisioning_plugin.yaml");
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, filepath);
        CountDownLatch logLatch =  new CountDownLatch(2);
        CountDownLatch reverseLatch = new CountDownLatch(1);
        DeviceConfiguration deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
        TestUtils.createCloseableLogListener((message) -> {
            String messageString = message.getMessage();
            if (messageString.contains("Device not configured to talk to AWS Iot cloud")) {
                logLatch.countDown();
            } else if ("Updated provisioning configuration".equals(messageString)) {
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
        });

        kernel.launch();
        assertTrue(logLatch.await(2, TimeUnit.SECONDS));
        assertFalse(reverseLatch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_nucleus_started_with_testProvisioningPlugin_AND_plugin_throws_non_retryable_exception(ExtensionContext context) throws Throwable {
        ignoreExceptionUltimateCauseOfType(context, NumberFormatException.class);
        URL filepath = getClass().getResource("config_with_test_provisioning_plugin.yaml");
        String configBody = new String(Files.readAllBytes(Paths.get(filepath.toURI())), StandardCharsets.UTF_8);
        String generatedCertFilePath = Files.createTempFile(certFilePath, null, ".pem")
                .toAbsolutePath().toString();
        configBody = replaceConfgPrameters(configBody, generatedCertFilePath, "InvalidNumber");
        Path configFilePath = Paths.get(String.valueOf(configurationFilePath), "config.yaml");
        Files.write(configFilePath, configBody.getBytes(StandardCharsets.UTF_8));

        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, configFilePath.toUri().toURL());

        CountDownLatch logLatch =  new CountDownLatch(1);
        TestUtils.createCloseableLogListener((message) -> {
            String messageString = message.getMessage();
            if (messageString.contains("Device not configured to talk to AWS Iot cloud")) {
                logLatch.countDown();
            }
        });
        CountDownLatch reverseLatch = new CountDownLatch(1);
        TestUtils.createCloseableLogListener((message) -> {
            String messageString = message.getMessage();
            if (messageString.contains("Updated provisioning configuration")) {
                reverseLatch.countDown();
            }
        });
        kernel.launch();
        assertTrue(logLatch.await(2, TimeUnit.SECONDS));
        assertFalse(reverseLatch.await(2, TimeUnit.SECONDS));
    }

    private String replaceConfgPrameters(String configTemplate, String generatedCertFilePath, String waitimeInMs) throws IOException {
        configTemplate = configTemplate.replace("$certfilepath", generatedCertFilePath);
        configTemplate = configTemplate.replace("$privatekeypath", Files.createTempFile(privateKeyPath, null, ".pem")
                .toAbsolutePath().toString());
        configTemplate = configTemplate.replace("$rootcapath", Files.createTempFile(rootCAPath, null, ".pem")
                .toAbsolutePath().toString());
        configTemplate = configTemplate.replace("$waittimems", waitimeInMs);
        return configTemplate;
    }

}
