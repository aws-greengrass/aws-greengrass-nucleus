package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.LocalDeploymentListener;
import com.aws.iot.evergreen.integrationtests.e2e.util.FileUtils;
import com.aws.iot.evergreen.kernel.Kernel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    @Test
    void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_task_THEN_services_start_in_kernel() throws Exception {
        String deploymentDocument = new String(Files.readAllBytes(
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("SampleJobDocument.json").toURI())));
        assertTrue(localDeploymentListener.submitLocalDeployment(deploymentDocument));

        CountDownLatch customerAppRunningLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if ("CustomerApp".equals(service.getName()) && newState.equals(State.RUNNING)) {
                customerAppRunningLatch.countDown();
            }
        });
        assertTrue(customerAppRunningLatch.await(50, TimeUnit.SECONDS));
    }

}
