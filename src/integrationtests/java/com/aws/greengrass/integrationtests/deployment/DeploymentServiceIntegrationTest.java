/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.FleetConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.ipc.IPCClientImpl;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.services.lifecycle.Lifecycle;
import com.aws.greengrass.ipc.services.lifecycle.LifecycleImpl;
import com.aws.greengrass.ipc.services.lifecycle.PreComponentUpdateEvent;
import com.aws.greengrass.ipc.services.lifecycle.exceptions.LifecycleIPCException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
public class DeploymentServiceIntegrationTest extends BaseITCase {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private Kernel kernel;
    private DeploymentQueue deploymentQueue;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        kernel = new Kernel();
        kernel.parseArgs("-i",
                DeploymentServiceIntegrationTest.class.getResource("onlyMain.yaml").toString());
        // ensure deployment service starts
        CountDownLatch deploymentServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(DEPLOYMENT_SERVICE_TOPICS) && newState.equals(State.RUNNING)) {
                deploymentServiceLatch.countDown();

            }
        });
        setDeviceConfig(kernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 1L);
        kernel.launch();
        assertTrue(deploymentServiceLatch.await(10, TimeUnit.SECONDS));
        deploymentQueue =  kernel.getContext().get(DeploymentQueue.class);

        FleetStatusService fleetStatusService = (FleetStatusService) kernel.locate(FLEET_STATUS_SERVICE_TOPICS);
        fleetStatusService.getIsConnected().set(false);
        // pre-load contents to package store
        Path localStoreContentPath =
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("local_store_content").toURI());
        copyFolderRecursively(localStoreContentPath, kernel.getNucleusPaths().componentStorePath(), REPLACE_EXISTING);
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_device_deployment_not_started_WHEN_new_deployment_THEN_first_deployment_cancelled() throws Exception {

        CountDownLatch cdlDeployNonDisruptable = new CountDownLatch(1);
        CountDownLatch cdlDeployRedSignal = new CountDownLatch(1);
        CountDownLatch cdlRedeployNonDisruptable = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {

            if (m.getMessage() != null) {
                if (m.getMessage().contains("Current deployment finished") && m.getContexts().get("DeploymentId").equals("deployNonDisruptable")) {
                    cdlDeployNonDisruptable.countDown();
                }
                if (m.getMessage().contains("Discarding device deployment") && m.getContexts().get("DEPLOYMENT_ID").equals("deployRedSignal")) {
                    cdlDeployRedSignal.countDown();
                }
                if (m.getMessage().contains("Current deployment finished") && m.getContexts().get("DeploymentId").equals("redeployNonDisruptable")) {
                    cdlRedeployNonDisruptable.countDown();
                }
            }
        };

        Slf4jLogAdapter.addGlobalListener(listener);
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithNonDisruptableService.json").toURI(),
                "deployNonDisruptable", DeploymentType.SHADOW);

        CountDownLatch nonDisruptableServiceServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("NonDisruptableService") && newState.equals(State.RUNNING)) {
                nonDisruptableServiceServiceLatch.countDown();

            }
        });
        assertTrue(nonDisruptableServiceServiceLatch.await(30, TimeUnit.SECONDS));

        KernelIPCClientConfig nonDisruptable = getIPCConfigForService("NonDisruptableService", kernel);
        IPCClientImpl ipcClient = new IPCClientImpl(nonDisruptable);
        Lifecycle lifecycle = new LifecycleImpl(ipcClient);
        lifecycle.subscribeToComponentUpdate((event) -> {
            if (event instanceof PreComponentUpdateEvent) {
                try {
                    lifecycle.deferComponentUpdate("NonDisruptableService", TimeUnit.SECONDS.toMillis(60));
                } catch (LifecycleIPCException e) {
                }
            }
        });
        assertTrue(cdlDeployNonDisruptable.await(30, TimeUnit.SECONDS));
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedSignalService.json").toURI(),
                "deployRedSignal", DeploymentType.SHADOW);
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithNonDisruptableService.json").toURI(),
                "redeployNonDisruptable", DeploymentType.SHADOW);
        assertTrue(cdlRedeployNonDisruptable.await(15, TimeUnit.SECONDS));
        ipcClient.disconnect();
        assertTrue(cdlDeployRedSignal.await(1, TimeUnit.SECONDS));
        Slf4jLogAdapter.removeGlobalListener(listener);
    }

    private void submitSampleJobDocument(URI uri, String arn, DeploymentType type) throws Exception {
        FleetConfiguration fleetConfiguration = OBJECT_MAPPER.readValue(new File(uri), FleetConfiguration.class);
        fleetConfiguration.setCreationTimestamp(System.currentTimeMillis());
        fleetConfiguration.setConfigurationArn(arn);
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(fleetConfiguration), type, fleetConfiguration.getConfigurationArn());
        deploymentQueue.offer(deployment);
    }
}
