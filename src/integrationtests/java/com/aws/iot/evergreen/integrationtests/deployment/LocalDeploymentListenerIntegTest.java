package com.aws.iot.evergreen.integrationtests.deployment;

import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.FindComponentVersionsByPlatformResult;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.LocalDeploymentListener;
import com.aws.iot.evergreen.integrationtests.e2e.util.FileUtils;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.GreengrassPackageServiceClientFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.deployment.LocalDeploymentListener.ComponentInfo;
import static com.aws.iot.evergreen.deployment.LocalDeploymentListener.ListComponentsResult;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsIterableContaining.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalDeploymentListenerIntegTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static Kernel kernel;
    private static LocalDeploymentListener localDeploymentListener;

    @TempDir
    static Path rootDir;

    @BeforeAll
    static void setupKernel() throws IOException, URISyntaxException {
        System.setProperty("root", rootDir.toAbsolutePath().toString());
        kernel = new Kernel();
        kernel.parseArgs("-i", DeploymentTaskIntegrationTest.class.getResource("onlyMain.yaml").toString());

        GreengrassPackageServiceClientFactory mockFactory = mock(GreengrassPackageServiceClientFactory.class);
        kernel.getContext().put(GreengrassPackageServiceClientFactory.class, mockFactory);
        AWSEvergreen mockEg = mock(AWSEvergreen.class);
        when(mockEg.findComponentVersionsByPlatform(any())).thenReturn(
                new FindComponentVersionsByPlatformResult().withComponents(Collections.emptyList()));
        when(mockFactory.getCmsClient()).thenReturn(mockEg);

        kernel.launch();
        localDeploymentListener = kernel.getContext().get(LocalDeploymentListener.class);

        Path localStoreContentPath = Paths.get(
                LocalDeploymentListenerIntegTest.class.getResource("local_store_content").toURI());
        // pre-load contents to package store
        FileUtils.copyFolderRecursively(localStoreContentPath, kernel.getPackageStorePath());
    }

    @AfterAll
    static void tearDown() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_task_THEN_services_start_in_kernel() throws Exception {
        String localOverrideRequestStr = new String(Files.readAllBytes(
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("Local_override_request.json").toURI())));
        assertTrue(localDeploymentListener.submitLocalDeployment(localOverrideRequestStr));

        CountDownLatch customerAppRunningLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if ("CustomerApp".equals(service.getName()) && newState.equals(State.RUNNING)) {
                customerAppRunningLatch.countDown();
            }
        });
        assertTrue(customerAppRunningLatch.await(50, TimeUnit.SECONDS));

        ListComponentsResult listComponentsResult = OBJECT_MAPPER.readValue(
                localDeploymentListener.listComponents(), ListComponentsResult.class);

        assertThat(listComponentsResult.getRootPackages(), hasItem("CustomerApp"));
        ComponentInfo customerApp = listComponentsResult.getComponentsInfo().stream()
                .filter(c -> c.getPackageName().equals("CustomerApp")).findAny().get();
        assertEquals("CustomerApp", customerApp.getPackageName());
        assertEquals("1.0.0", customerApp.getVersion());
        assertEquals("This is a test", customerApp.getRuntimeParameters().get("sampleText"));

        ComponentInfo mosquittoApp = listComponentsResult.getComponentsInfo().stream()
                .filter(c -> c.getPackageName().equals("Mosquitto")).findAny().get();
        assertNotNull(mosquittoApp);
        ComponentInfo greenSignalApp = listComponentsResult.getComponentsInfo().stream()
                .filter(c -> c.getPackageName().equals("GreenSignal")).findAny().get();
        assertNotNull(greenSignalApp);
    }
}
