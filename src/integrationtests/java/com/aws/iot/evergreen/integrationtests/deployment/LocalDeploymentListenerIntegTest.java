package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.LocalDeploymentListener;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.deployment.LocalDeploymentListener.ComponentInfo;
import static com.aws.iot.evergreen.deployment.LocalDeploymentListener.ListComponentsResult;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.iot.evergreen.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsIterableContaining.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
class LocalDeploymentListenerIntegTest {
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
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

        kernel.launch();
        localDeploymentListener = kernel.getContext().get(LocalDeploymentListener.class);

        Path localStoreContentPath =
                Paths.get(LocalDeploymentListenerIntegTest.class.getResource("local_store_content").toURI());
        // pre-load contents to package store
        copyFolderRecursively(localStoreContentPath, kernel.getPackageStorePath(), REPLACE_EXISTING);
    }

    @AfterAll
    static void tearDown() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_task_THEN_services_start_in_kernel(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);

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

        ListComponentsResult listComponentsResult =
                OBJECT_MAPPER.readValue(localDeploymentListener.listComponents(), ListComponentsResult.class);

        assertThat(listComponentsResult.getRootPackages(), hasItem("CustomerApp"));
        ComponentInfo customerApp =
                listComponentsResult.getComponentsInfo().stream().filter(c -> c.getPackageName().equals("CustomerApp"))
                        .findAny().get();
        assertEquals("CustomerApp", customerApp.getPackageName());
        assertEquals("1.0.0", customerApp.getVersion());
        assertEquals("This is a test", customerApp.getRuntimeParameters().get("sampleText"));

        ComponentInfo mosquittoApp =
                listComponentsResult.getComponentsInfo().stream().filter(c -> c.getPackageName().equals("Mosquitto"))
                        .findAny().get();
        assertNotNull(mosquittoApp);
        ComponentInfo greenSignalApp =
                listComponentsResult.getComponentsInfo().stream().filter(c -> c.getPackageName().equals("GreenSignal"))
                        .findAny().get();
        assertNotNull(greenSignalApp);
    }
}
