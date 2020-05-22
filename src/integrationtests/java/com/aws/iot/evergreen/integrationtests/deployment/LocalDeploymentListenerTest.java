package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.LocalDeploymentListener;
import com.aws.iot.evergreen.integrationtests.e2e.util.FileUtils;
import com.aws.iot.evergreen.kernel.Kernel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocalDeploymentListenerTest {

    private static Kernel kernel;
    private static LocalDeploymentListener localDeploymentListener;

    @BeforeAll
    static void setupKernel() throws IOException {
        kernel = new Kernel();
        kernel.parseArgs("-i", DeploymentTaskIntegrationTest.class.getResource("onlyMain.yaml").toString());
        kernel.launch();
        localDeploymentListener = kernel.getContext().get(LocalDeploymentListener.class);

        Path localStoreContentPath = Paths.get(LocalDeploymentListenerTest.class.getResource("local_store_content").getPath());
        // pre-load contents to package store
        FileUtils.copyFolderRecursively(localStoreContentPath, kernel.getPackageStorePath());
    }

    @AfterAll
    static void tearDown() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_task_THEN_services_start_in_kernel() throws Exception {
        String deploymentDocument = new String(Files.readAllBytes(
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("YellowAndRedSignal.json").toURI())));
        assertTrue(localDeploymentListener.submitLocalDeployment(deploymentDocument));

        CountDownLatch yellowAppRunningLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if ("YellowSignal".equals(service.getName()) && newState.equals(State.RUNNING)) {
                yellowAppRunningLatch.countDown();
            }
        });
        assertTrue(yellowAppRunningLatch.await(50, TimeUnit.SECONDS));
        Map<String, String> rootPackageNameAndVersion = localDeploymentListener.getRootPackageNameAndVersion();
        assertEquals(2, rootPackageNameAndVersion.size());
        assertEquals("1.0.0", rootPackageNameAndVersion.get("YellowSignal"));
        assertEquals("1.0.0", rootPackageNameAndVersion.get("RedSignal"));
    }

}
