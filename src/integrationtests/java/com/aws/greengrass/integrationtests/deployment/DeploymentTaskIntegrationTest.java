/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DefaultDeploymentTask;
import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.DeploymentDocumentDownloader;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.ThingGroupHelper;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.ipc.IPCTestUtils;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Semver;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsMapContaining;
import org.hamcrest.collection.IsMapWithSize;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.event.Level;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.ComponentUpdatePolicyEvents;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesResponse;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.POSIX_USER_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SYSTEM_RESOURCE_LIMITS_TOPICS;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.WINDOWS_USER_KEY;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.SudoUtil.assumeCanSudoShell;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SuppressWarnings("PMD.ExcessiveClassLength")
        // This test is essential and verified many details. Could be breakdown.
class DeploymentTaskIntegrationTest extends BaseITCase {

    public static final String SIMPLE_APP_NAME = "SimpleApp";
    private static final String TEST_CUSTOMER_APP_STRING = "Hello Greengrass. This is a test";
    private static final String MOCK_GROUP_NAME = "thinggroup/group1";
    // Based on the recipe files of the packages in sample job document
    private static final String TEST_MOSQUITTO_STRING = "Hello this is mosquitto getting started";
    private static final String TEST_TICK_TOCK_STRING = "Go ahead with 2 approvals";
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final AtomicInteger deploymentCount = new AtomicInteger();
    private static final int STDOUT_TIMEOUT = 40;
    private static final int DEPLOYMENT_TIMEOUT = 80;

    private static Logger logger;
    private static DependencyResolver dependencyResolver;
    private static ComponentManager componentManager;
    private static ComponentStore componentStore;
    private static KernelConfigResolver kernelConfigResolver;
    private static DeploymentConfigMerger deploymentConfigMerger;
    private static Kernel kernel;

    private static Map<String, Long> outputMessagesToTimestamp;
    private static SocketOptions socketOptions;
    private static DeploymentDocumentDownloader deploymentDocumentDownloader;
    private static Path rootDir;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private DeploymentDocument sampleJobDocument;
    private CountDownLatch countDownLatch;
    private Topics groupToRootComponentsTopics;
    private Topics deploymentServiceTopics;
    private static ThingGroupHelper thingGroupHelper;

    @BeforeAll
    static void initialize() {
        outputMessagesToTimestamp = new HashMap<>();
        socketOptions = TestUtils.getSocketOptionsForIPC();
        logger = LogManager.getLogger(DeploymentTaskIntegrationTest.class);
    }

    @BeforeAll
    static void setupKernel() throws IOException {
        kernel = new Kernel();
        rootDir = Paths.get(System.getProperty("root"));
        NoOpPathOwnershipHandler.register(kernel);

        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                DeploymentTaskIntegrationTest.class.getResource("noMain.yaml"));

        kernel.launch();
        // get required instances from context
        componentManager = kernel.getContext().get(ComponentManager.class);
        componentStore = kernel.getContext().get(ComponentStore.class);
        dependencyResolver = kernel.getContext().get(DependencyResolver.class);
        kernelConfigResolver = kernel.getContext().get(KernelConfigResolver.class);
        deploymentConfigMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        deploymentDocumentDownloader = kernel.getContext().get(DeploymentDocumentDownloader.class);
        thingGroupHelper =  kernel.getContext().get(ThingGroupHelper.class);
    }

    @AfterAll
    static void tearDown() {
        if (socketOptions != null) {
            socketOptions.close();
        }
        kernel.shutdown();
    }

    private static void assertRecipeArtifactExists(ComponentIdentifier compId) throws PackageLoadingException {
        assertThat(componentStore.resolveRecipePath(compId).toFile(), anExistingFile());
        Path artifactDirPath = kernel.getNucleusPaths().artifactPath().resolve(compId.getName())
                .resolve(compId.getVersion().getValue());
        assertThat(artifactDirPath.toFile(), anExistingDirectory());
    }

    private static void assertRecipeArtifactNotExists(ComponentIdentifier compId) throws PackageLoadingException {
        assertThat(componentStore.resolveRecipePath(compId).toFile(), not(anExistingFile()));
        Path artifactDirPath = kernel.getNucleusPaths().artifactPath().resolve(compId.getName())
                .resolve(compId.getVersion().getValue());
        assertThat(artifactDirPath.toFile(), not(anExistingDirectory()));
    }

    /* sync packages directory with local_store_content */
    private static void preloadLocalStoreContent() throws URISyntaxException, IOException {
        Path localStoreContentPath =
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("local_store_content").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(),
                REPLACE_EXISTING);
    }

    /* just copy recipe and artifacts of a single component-version */
    private static void preloadLocalStoreContent(String compName, String version)
            throws URISyntaxException, IOException {
        String recipeFileName = String.format("%s-%s.yaml", compName,
                version); // naming convention under 'local_store_content/recipes/'

        Path localStoreContentPath =
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("local_store_content").toURI());

        // copy over recipe
        Path recipeFromTestResource = localStoreContentPath.resolve("recipes").resolve(recipeFileName);
        Path destRecipe = kernel.getNucleusPaths().recipePath()
                .resolve(PreloadComponentStoreHelper.getRecipeStorageFilenameFromTestSource(recipeFileName));
        Files.copy(recipeFromTestResource, destRecipe, REPLACE_EXISTING);

        // copy over artifacts
        copyFolderRecursively(resolveArtifactPathFromCompStoreRoot(localStoreContentPath, compName, version),
                resolveArtifactPathFromCompStoreRoot(kernel.getNucleusPaths().componentStorePath(), compName, version),
                REPLACE_EXISTING);
    }

    private static Path resolveArtifactPathFromCompStoreRoot(Path compStoreRootPath, String name, String version) {
        return compStoreRootPath.resolve("artifacts").resolve(name).resolve(version);
    }

    @BeforeEach
    void beforeEach(ExtensionContext context) throws Exception {
        // This test suite will not be able to call cloud as it uses all local resources
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionOfType(context, SdkClientException.class);

        deploymentServiceTopics = Topics.of(kernel.getContext(), DeploymentService.DEPLOYMENT_SERVICE_TOPICS, null);
        groupToRootComponentsTopics =
                deploymentServiceTopics.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS, MOCK_GROUP_NAME);

        // pre-load contents to package store
        preloadLocalStoreContent();

        if (!PlatformResolver.isWindows) {
            assumeCanSudoShell(kernel);
        }
        kernel.getContext().waitForPublishQueueToClear();
    }

    @AfterEach
    void afterEach() {
        executorService.shutdownNow();
        LogManager.getRootLogConfiguration().setLevel(Level.INFO);
    }

    /**
     * Start with a fresh kernel. Deploy a component with broken install script. Deployment should fail and rollback
     * successfully. This test needs to happen before all other test cases since it requires a fresh kernel.
     */
    @Test
    @Order(0)
    void GIVEN_kernel_with_no_deployment_history_WHEN_new_service_install_breaks_THEN_services_are_rolled_back(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceUpdateException.class);

        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("Failure3RollbackDeployment.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        // should only contain main, Nucleus
        assertEquals(2, services.size());
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("ComponentWithBrokenInstall"));
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, result.getDeploymentStatus());
    }

    /**
     * Deploy versions 1.0.0 through 4.0.0 sequentially. Stale version should be removed. In this test we need to
     * preload recipe/artifact before a deployment so that it can be found locally, because unused local files are
     * removed by cleanup from previous deployment. After this we'll reload local files again so that the following
     * tests can proceed normally.
     */
    @Test
    @Order(1)
    void GIVEN_component_with_multiple_versions_WHEN_deploy_sequentially_THEN_stale_version_removed() throws Exception {
        ComponentIdentifier simpleApp1 = new ComponentIdentifier(SIMPLE_APP_NAME, new Semver("1.0.0"));
        ComponentIdentifier simpleApp2 = new ComponentIdentifier(SIMPLE_APP_NAME, new Semver("2.0.0"));

        // deploy version 1
        Future<DeploymentResult> resultFuture1 = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("SimpleAppJobDoc1.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result1 = resultFuture1.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result1.getDeploymentStatus());

        // version 2 should not exist now. preload it before deployment. we'll do the same for later deployments
        assertRecipeArtifactNotExists(simpleApp2);
        preloadLocalStoreContent(SIMPLE_APP_NAME, "2.0.0");

        // deploy version 2
        Future<DeploymentResult> resultFuture2 = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("SimpleAppJobDoc2.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result2 = resultFuture2.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result2.getDeploymentStatus());

        // both 1 and 2 should exist in component store at this point
        assertRecipeArtifactExists(simpleApp1);
        assertRecipeArtifactExists(simpleApp2);

        // deploy version 3
        preloadLocalStoreContent(SIMPLE_APP_NAME, "3.0.0");
        Future<DeploymentResult> resultFuture3 = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("SimpleAppJobDoc3.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result3 = resultFuture3.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result3.getDeploymentStatus());

        // version 1 removed by preemptive cleanup
        assertRecipeArtifactNotExists(simpleApp1);

        // deploy version 4
        preloadLocalStoreContent(SIMPLE_APP_NAME, "4.0.0");
        Future<DeploymentResult> resultFuture4 = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("SimpleAppJobDoc4.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result4 = resultFuture4.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result4.getDeploymentStatus());

        // version 2 removed by preemptive cleanup
        assertRecipeArtifactNotExists(simpleApp2);
    }

    /**
     * Deploy version 1, 2, and 1 again. Then 1 should not be cleaned up. If cleanup buggy this can fail
     */
    @Test
    @Order(2)
    void GIVEN_component_with_multiple_versions_WHEN_deploy_previous_version_THEN_running_version_not_cleaned_up()
            throws Exception {
        ComponentIdentifier simpleApp1 = new ComponentIdentifier(SIMPLE_APP_NAME, new Semver("1.0.0"));

        // deploy version 1 and 2
        preloadLocalStoreContent(SIMPLE_APP_NAME, "1.0.0");
        Future<DeploymentResult> resultFuture1 = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("SimpleAppJobDoc1.json").toURI(),
                System.currentTimeMillis());
        resultFuture1.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);

        preloadLocalStoreContent(SIMPLE_APP_NAME, "2.0.0");
        Future<DeploymentResult> resultFuture2 = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("SimpleAppJobDoc2.json").toURI(),
                System.currentTimeMillis());
        resultFuture2.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);

        // deploy V1 again
        Future<DeploymentResult> resultFuture1Again = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("SimpleAppJobDoc1.json").toURI(),
                System.currentTimeMillis());
        resultFuture1Again.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        assertRecipeArtifactExists(simpleApp1);

        // load files again for the subsequent tests
        preloadLocalStoreContent();
    }

    @Test
    @Order(3)
    void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_task_THEN_services_start_in_kernel() throws Exception {
        ((Map) kernel.getContext().getvIfExists(Kernel.SERVICE_TYPE_TO_CLASS_MAP_KEY).get())
                .put("plugin", GreengrassService.class.getName());
        outputMessagesToTimestamp.clear();
        final List<String> listOfExpectedMessages =
                Arrays.asList(TEST_TICK_TOCK_STRING, TEST_MOSQUITTO_STRING, TEST_CUSTOMER_APP_STRING);
        countDownLatch = new CountDownLatch(3);
        Consumer<GreengrassLogMessage> listener = m -> {
            String messageOnStdout = m.getMessage();
            if (messageOnStdout == null) {
                return;
            }
            // Windows has quotes in the echo, so strip them
            messageOnStdout = messageOnStdout.replaceAll("\"", "");
            if (listOfExpectedMessages.contains(messageOnStdout)) {
                // GG_NEEDS_REVIEW: TODO: Deduping is needed, as currently kernel is running the GreenSignal and Mosquitto dependencies
                // multiple times before the CustomerApp runs. This should not be the expected behavior. Sim to
                // capture this https://sim.amazon.com/issues/P34042537
                if (!outputMessagesToTimestamp.containsKey(messageOnStdout)) {
                    outputMessagesToTimestamp.put(messageOnStdout, m.getTimestamp());
                    countDownLatch.countDown();
                }
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);
        try {
            Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("SampleJobDocument.json").toURI(),
                    System.currentTimeMillis());

            resultFuture.get(10, TimeUnit.SECONDS);

            countDownLatch.await(10, TimeUnit.SECONDS);
            Set<String> listOfStdoutMessagesTapped = outputMessagesToTimestamp.keySet();
            assertThat(listOfStdoutMessagesTapped, containsInAnyOrder(Matchers.equalTo(TEST_CUSTOMER_APP_STRING),
                    Matchers.equalTo(TEST_MOSQUITTO_STRING), Matchers.equalTo(TEST_TICK_TOCK_STRING)));
        } finally {
            Slf4jLogAdapter.removeGlobalListener(listener);
        }

        // Check that ClassService is a raw GreengrassService and not a GenericExternalService
        assertEquals(GreengrassService.class, kernel.locate("ClassService").getClass());
    }

    @Test
    @Order(4)
    void GIVEN_multiple_deployments_with_config_update_WHEN_submitted_to_deployment_task_THEN_configs_are_updated()
            throws Exception {
        LogManager.getRootLogConfiguration().setLevel(Level.TRACE);

        // Two things are verified in this test
        // 1. The component's configurations are updated correctly in the kernel's config store
        // 2. The interpolation is correct by taking the newly updated configuration, that is consistent

        // Set up stdout listener to capture stdout for verify #2 interpolation
        List<String> stdouts = new CopyOnWriteArrayList<>();
        Consumer<GreengrassLogMessage> listener = m -> {
            String messageOnStdout = m.getMessage();
            if (messageOnStdout != null && messageOnStdout
                    .contains("aws.iot.gg.test.integ.ComponentConfigTestService output")) {
                stdouts.add(messageOnStdout);
                countDownLatch.countDown(); // countdown when received output to verify
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);
        try {

            /*
             * 1st deployment. Default Config.
             */
            countDownLatch = new CountDownLatch(1);
            Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("ComponentConfigTest_DeployDocument_1.json")
                            .toURI(), System.currentTimeMillis());
            resultFuture.get(10, TimeUnit.SECONDS);

            // verify config in config store and interpolation result
            Map<String, Object> resultConfig =
                    kernel.findServiceTopic("aws.iot.gg.test.integ.ComponentConfigTestService")
                            .findTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();

            verifyDefaultValueIsApplied(stdouts, resultConfig);

            /*
             * 2nd deployment. MERGE existing keys.
             */
            countDownLatch = new CountDownLatch(1); // reset countdown
            resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("ComponentConfigTest_DeployDocument_2.json")
                            .toURI(), System.currentTimeMillis());
            resultFuture.get(10, TimeUnit.SECONDS);

            // verify config in config store
            resultConfig = kernel.findServiceTopic("aws.iot.gg.test.integ.ComponentConfigTestService")
                    .findTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();

            // Asserted values can be found in ComponentConfigTest_DeployDocument_2.json
            assertThat(resultConfig, IsMapContaining.hasEntry("singleLevelKey", "updated value of singleLevelKey"));
            assertThat(resultConfig, IsMapContaining.hasEntry("listKey", Collections.singletonList("item3")));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyStringKey", ""));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyListKey", Collections.emptyList()));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyObjectKey", Collections.emptyMap()));
            assertThat(resultConfig, IsMapContaining.hasEntry("defaultIsNullKey", "updated value of defaultIsNullKey"));
            assertThat(resultConfig, IsMapContaining.hasEntry("willBeNullKey", null));

            assertThat(resultConfig, IsMapContaining.hasKey("path"));
            assertThat(resultConfig, IsMapWithSize.aMapWithSize(8));    // no more keys

            assertThat((Map<String, String>) resultConfig.get("path"),
                    IsMapContaining.hasEntry("leafKey", "updated value of /path/leafKey"));
            assertThat((Map<String, String>) resultConfig.get("path"), IsMapWithSize.aMapWithSize(1));  // no more keys

            // verify interpolation result
            assertThat("The stdout should be captured within seconds.", countDownLatch.await(STDOUT_TIMEOUT, TimeUnit.SECONDS));
            String stdout = stdouts.get(0);

            assertTrue(stdouts.get(0).contains("Value for /singleLevelKey: updated value of singleLevelKey."));
            assertTrue(stdouts.get(0).contains("Value for /path/leafKey: updated value of /path/leafKey."));
            assertTrue(stdouts.get(0).contains("Value for /listKey/0: item3."));
            assertTrue(stdouts.get(0).contains("Value for /emptyStringKey: ."));
            assertTrue(stdouts.get(0).contains("Value for /defaultIsNullKey: updated value of defaultIsNullKey."));
            assertTrue(stdouts.get(0).contains("Value for /newSingleLevelKey: {configuration:/newSingleLevelKey}."));
            stdouts.clear();

            /*
             * 3rd deployment MERGE not existed keys
             */
            countDownLatch = new CountDownLatch(1); // reset countdown
            resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("ComponentConfigTest_DeployDocument_3.json")
                            .toURI(), System.currentTimeMillis());
            resultFuture.get(10, TimeUnit.SECONDS);

            // verify config in config store
            resultConfig = kernel.findServiceTopic("aws.iot.gg.test.integ.ComponentConfigTestService")
                    .findTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();
            assertThat(resultConfig, IsMapContaining.hasEntry("singleLevelKey", "updated value of singleLevelKey"));
            assertThat(resultConfig, IsMapContaining.hasEntry("listKey", Collections.singletonList("item3")));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyStringKey", ""));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyListKey", Collections.emptyList()));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyObjectKey", Collections.emptyMap()));
            assertThat(resultConfig, IsMapContaining.hasEntry("defaultIsNullKey", "updated value of defaultIsNullKey"));
            assertThat(resultConfig, IsMapContaining.hasEntry("willBeNullKey", null));

            assertThat(resultConfig, IsMapContaining.hasKey("path"));
            assertThat(resultConfig, IsMapContaining.hasEntry("newSingleLevelKey", "value of newSingleLevelKey"));
            assertThat(resultConfig, IsMapWithSize.aMapWithSize(9));    // no more keys

            assertThat((Map<String, String>) resultConfig.get("path"),
                    IsMapContaining.hasEntry("leafKey", "updated value of /path/leafKey"));
            assertThat((Map<String, String>) resultConfig.get("path"),
                    IsMapContaining.hasEntry("newLeafKey", "value of /path/newLeafKey"));
            assertThat((Map<String, String>) resultConfig.get("path"), IsMapWithSize.aMapWithSize(2));  // no more keys

            // verify interpolation result
            assertThat("The stdout should be captured within seconds.", countDownLatch.await(STDOUT_TIMEOUT, TimeUnit.SECONDS));
            stdout = stdouts.get(0);

            assertThat(stdout, containsString("Value for /singleLevelKey: updated value of singleLevelKey."));
            assertThat(stdout, containsString("Value for /path/leafKey: updated value of /path/leafKey."));
            assertThat(stdout, containsString("Value for /listKey/0: item3."));
            assertThat(stdout, containsString("Value for /emptyStringKey: ."));
            assertThat(stdout, containsString("Value for /defaultIsNullKey: updated value of defaultIsNullKey."));
            assertThat(stdout, containsString("Value for /newSingleLevelKey: value of newSingleLevelKey."));
            stdouts.clear();

            /*
             * 4th deployment. RESET.
             */
            countDownLatch = new CountDownLatch(1); // reset countdown
            resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("ComponentConfigTest_DeployDocument_4.json")
                            .toURI(), System.currentTimeMillis());
            resultFuture.get(10, TimeUnit.SECONDS);

            // verify config in config store
            resultConfig = kernel.findServiceTopic("aws.iot.gg.test.integ.ComponentConfigTestService")
                    .findTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();
            assertThat(resultConfig, IsMapContaining.hasEntry("singleLevelKey", "updated value of singleLevelKey"));
            assertThat(resultConfig, IsMapContaining.hasEntry("listKey", Arrays.asList("item1", "item2")));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyStringKey", ""));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyListKey", Collections.emptyList()));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyObjectKey", Collections.emptyMap()));
            assertThat(resultConfig, IsMapContaining.hasEntry("defaultIsNullKey", "updated value of defaultIsNullKey"));
            assertThat(resultConfig, IsMapContaining.hasEntry("willBeNullKey", null));

            assertThat(resultConfig, IsMapContaining.hasKey("path"));
            assertThat((Map<String, String>) resultConfig.get("path"),
                    IsMapContaining.hasEntry("leafKey", "updated value of /path/leafKey"));

            assertFalse(resultConfig.containsKey("newSingleLevelKey"),
                    "newSingleLevelKey should be cleared after RESET because it doesn't have a default value");
            assertThat(resultConfig, IsMapWithSize.aMapWithSize(8));    // no more keys

            assertFalse(((Map<String, String>) resultConfig.get("path")).containsKey("newLeafKey"),
                    "/path/newSingleLevelKey should be cleared after RESET because it doesn't have a default value");
            assertThat((Map<String, String>) resultConfig.get("path"), IsMapWithSize.aMapWithSize(1));  // no more keys

            // verify interpolation result
            assertThat("The stdout should be captured within seconds.", countDownLatch.await(STDOUT_TIMEOUT, TimeUnit.SECONDS));
            stdout = stdouts.get(0);

            assertThat(stdout, containsString("Value for /singleLevelKey: updated value of singleLevelKey."));
            assertThat(stdout, containsString("Value for /path/leafKey: updated value of /path/leafKey."));
            assertThat(stdout, containsString("Value for /listKey/0: item1."));
            assertThat(stdout, containsString("Value for /emptyStringKey: ."));
            assertThat(stdout, containsString("Value for /defaultIsNullKey: updated value of defaultIsNullKey."));
            assertThat(stdout, containsString("Value for /newSingleLevelKey: {configuration:/newSingleLevelKey}."));
            stdouts.clear();

            // 5th RESET entirely to default
            countDownLatch = new CountDownLatch(1); // reset countdown
            resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("ComponentConfigTest_DeployDocument_5.json")
                            .toURI(), System.currentTimeMillis());
            resultFuture.get(10, TimeUnit.SECONDS);

            // verify config in config store and interpolation result
            resultConfig = kernel.findServiceTopic("aws.iot.gg.test.integ.ComponentConfigTestService")
                    .findTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();
            verifyDefaultValueIsApplied(stdouts, resultConfig);
        } finally {
            Slf4jLogAdapter.removeGlobalListener(listener);
        }
    }

    private void verifyDefaultValueIsApplied(List<String> stdouts, Map<String, Object> resultConfig)
            throws InterruptedException {
        // Asserted default values are from the aws.iot.gg.test.integ.ComponentConfigTestService-1.0.0.yaml recipe file
        assertThat(resultConfig, IsMapWithSize.aMapWithSize(8));
        assertThat(resultConfig, IsMapContaining.hasEntry("singleLevelKey", "default value of singleLevelKey"));
        assertThat(resultConfig, IsMapContaining.hasEntry("listKey", Arrays.asList("item1", "item2")));
        assertThat(resultConfig, IsMapContaining.hasEntry("emptyStringKey", ""));
        assertThat(resultConfig, IsMapContaining.hasEntry("emptyListKey", Collections.emptyList()));
        assertThat(resultConfig, IsMapContaining.hasEntry("emptyObjectKey", Collections.emptyMap()));
        assertThat(resultConfig, IsMapContaining.hasEntry("defaultIsNullKey", null));
        assertThat(resultConfig, IsMapContaining.hasEntry("willBeNullKey", "I will be set to null soon"));


        assertThat(resultConfig, IsMapContaining.hasKey("path"));
        assertThat((Map<String, String>) resultConfig.get("path"),
                IsMapContaining.hasEntry("leafKey", "default value of /path/leafKey"));

        // verify interpolation result
        assertThat("The stdout should be captured within seconds.", countDownLatch.await(120, TimeUnit.SECONDS));
        String stdout = stdouts.get(0);

        assertThat(stdout, containsString("Value for /singleLevelKey: default value of singleLevelKey."));
        assertThat(stdout, containsString("Value for /path/leafKey: default value of /path/leafKey."));
        assertThat(stdout, containsString("Value for /path: {\"leafKey\":\"default value of /path/leafKey\"}"));

        assertThat(stdout, containsString("Value for /listKey/0: item1."));
        assertThat(stdout, containsString("Value for /defaultIsNullKey: null"));
        assertThat(stdout, containsString("Value for /emptyStringKey: ."));
        assertThat(stdout, containsString("Value for /newSingleLevelKey: {configuration:/newSingleLevelKey}."));
        stdouts.clear();
    }

    @Test
    @Order(6)
    void GIVEN_initial_deployment_with_config_update_WHEN_submitted_to_deployment_task_THEN_configs_updates_on_default()
            throws Exception {

        // Two things are verified in this test
        // 1. The component's configurations are updated correctly in the kernel's config store
        // 2. The interpolation is correct by taking the newly updated configuration, that is consistent

        // Set up stdout listener to capture stdout for verify #2 interpolation
        List<String> stdouts = new CopyOnWriteArrayList<>();
        Consumer<GreengrassLogMessage> listener = m -> {
            String messageOnStdout = m.getMessage();
            if (messageOnStdout != null && messageOnStdout
                    .contains("aws.iot.gg.test.integ.ComponentConfigTestService output")) {
                stdouts.add(messageOnStdout);
                countDownLatch.countDown(); // countdown when received output to verify
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);
        try {

            /*
             * Initial deployment with configuration update
             */
            countDownLatch = new CountDownLatch(1);
            Future<DeploymentResult> resultFuture = submitSampleJobDocument(DeploymentTaskIntegrationTest.class
                            .getResource("ComponentConfigTest_InitialDocumentWithUpdate.json").toURI(),
                    System.currentTimeMillis());
            resultFuture.get(10, TimeUnit.SECONDS);

            // verify config in config store and interpolation result
            Map<String, Object> resultConfig =
                    kernel.findServiceTopic("aws.iot.gg.test.integ.ComponentConfigTestService")
                            .findTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();

            assertThat(resultConfig, IsMapWithSize.aMapWithSize(9));

            // verify updated values, as specified from ComponentConfigTest_InitialDocumentWithUpdate.json
            assertThat(resultConfig, IsMapContaining.hasEntry("singleLevelKey", "updated value of singleLevelKey"));
            assertThat(resultConfig, IsMapContaining.hasEntry("newSingleLevelKey", "value of newSingleLevelKey"));

            // verify default values from the aws.iot.gg.test.integ.ComponentConfigTestService-1.0.0.yaml recipe file
            assertThat(resultConfig, IsMapContaining.hasEntry("listKey", Arrays.asList("item1", "item2")));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyStringKey", ""));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyListKey", Collections.emptyList()));
            assertThat(resultConfig, IsMapContaining.hasEntry("emptyObjectKey", Collections.emptyMap()));
            assertThat(resultConfig, IsMapContaining.hasEntry("defaultIsNullKey", null));
            assertThat(resultConfig, IsMapContaining.hasEntry("willBeNullKey", "I will be set to null soon"));

            assertThat(resultConfig, IsMapContaining.hasKey("path"));
            assertThat((Map<String, String>) resultConfig.get("path"),
                    IsMapContaining.hasEntry("leafKey", "default value of /path/leafKey"));

            // verify interpolation result
            assertThat("The stdout should be captured within seconds.", countDownLatch.await(STDOUT_TIMEOUT, TimeUnit.SECONDS));
            String stdout = stdouts.get(0);

            // verify updated value, as specified from ComponentConfigTest_InitialDocumentWithUpdate.json
            assertThat(stdout, containsString("Value for /singleLevelKey: updated value of singleLevelKey."));
            assertThat(stdout, containsString("Value for /newSingleLevelKey: value of newSingleLevelKey."));

            // verify default values from the aws.iot.gg.test.integ.ComponentConfigTestService-1.0.0.yaml recipe file
            assertThat(stdout, containsString("Value for /path/leafKey: default value of /path/leafKey."));
            assertThat(stdout, containsString("Value for /path: {\"leafKey\":\"default value of /path/leafKey\"}"));

            assertThat(stdout, containsString("Value for /listKey/0: item1."));
            assertThat(stdout, containsString("Value for /defaultIsNullKey: null"));
            assertThat(stdout, containsString("Value for /emptyStringKey: ."));
            stdouts.clear();

        } finally {
            Slf4jLogAdapter.removeGlobalListener(listener);
        }
    }

    @Test
    @Order(5)
    void GIVEN_a_deployment_with_dependency_has_config_WHEN_submitted_THEN_dependency_configs_are_interpolated()
            throws Exception {
        // Set up stdout listener to capture stdout for verify #2 interpolation
        countDownLatch = new CountDownLatch(1);
        List<String> stdouts = new CopyOnWriteArrayList<>();
        Consumer<GreengrassLogMessage> listener = m -> {
            String messageOnStdout = m.getMessage();
            if (messageOnStdout != null && messageOnStdout
                    .contains("aws.iot.gg.test.integ.ComponentConfigTestMain output")) {
                countDownLatch.countDown();
                stdouts.add(messageOnStdout);
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);

        try {
            /*
             * 1st deployment. Default Config.
             */
            Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("CrossComponentConfigTest_DeployDocument.json")
                            .toURI(), System.currentTimeMillis());
            resultFuture.get(10, TimeUnit.SECONDS);

            // verify interpolation result
            assertThat("The stdout should be captured within seconds.", countDownLatch.await(STDOUT_TIMEOUT, TimeUnit.SECONDS));
            String stdout = stdouts.get(0);
            assertThat(stdout, containsString("Value for /singleLevelKey: default value of singleLevelKey."));
            assertThat(stdout, containsString("Value for /path/leafKey: default value of /path/leafKey."));
            assertThat(stdout, containsString("Value for /listKey/0: item1."));
            assertThat(stdout, containsString("Value for /emptyStringKey: ."));
        } finally {
            Slf4jLogAdapter.removeGlobalListener(listener);
        }
    }

    @Test
    @Order(7)
    void GIVEN_a_deployment_has_component_use_system_config_WHEN_submitted_THEN_system_configs_are_interpolated()
            throws Exception {

        CountDownLatch stdoutLatch = new CountDownLatch(1);
        // Set up stdout listener to capture stdout for verify #2 interpolation
        List<String> stdouts = new CopyOnWriteArrayList<>();
        Consumer<GreengrassLogMessage> listener = m -> {
            String messageOnStdout = m.getMessage();
            if (messageOnStdout != null && messageOnStdout.contains("aws.iot.gg.test.integ.SystemConfigTest output")) {
                stdouts.add(messageOnStdout);
                stdoutLatch.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);

        try {
            /*
             * 1st deployment. Default Config.
             */
            Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("SystemConfigTest_DeployDocument.json").toURI(),
                    System.currentTimeMillis());
            resultFuture.get(10, TimeUnit.SECONDS);

            // The main comes from SystemConfigTest_DeployDocument.json
            String mainComponentName = "aws.iot.gg.test.integ.SystemConfigTest";
            String mainComponentNameVer = "0.0.1";

            // The dependency is specified in aws.iot.gg.test.integ.SystemConfigTest-0.1.1
            String otherComponentName = "GreenSignal";
            String otherComponentVer = "1.0.0";

            assertThat("has output", stdoutLatch.await(STDOUT_TIMEOUT, TimeUnit.SECONDS), is(true));

            // verify interpolation result
            assertThat(stdouts.get(0), containsString("I'm kernel's root path: " + rootDir.toAbsolutePath()));

            assertThat(stdouts.get(0), containsString("I'm my own artifact path: " + rootDir.resolve("packages")
                    .resolve(ComponentStore.ARTIFACT_DIRECTORY).resolve(mainComponentName).resolve(mainComponentNameVer)
                    .toAbsolutePath()));

            assertTrue(stdouts.get(0).contains("I'm my own artifact decompressed path: " + rootDir.resolve("packages")
                    .resolve(ComponentStore.ARTIFACTS_DECOMPRESSED_DIRECTORY).resolve(mainComponentName)
                    .resolve(mainComponentNameVer).toAbsolutePath()));


            assertThat(stdouts.get(0), containsString("I'm GreenSignal's artifact path: " + rootDir.resolve("packages")
                    .resolve(ComponentStore.ARTIFACT_DIRECTORY).resolve(otherComponentName).resolve(otherComponentVer)
                    .toAbsolutePath()));

            assertThat(stdouts.get(0), containsString(
                    "I'm GreenSignal's artifact decompressed path: " + rootDir.resolve("packages")
                            .resolve(ComponentStore.ARTIFACTS_DECOMPRESSED_DIRECTORY).resolve(otherComponentName)
                            .resolve(otherComponentVer).toAbsolutePath()));
        } finally {
            Slf4jLogAdapter.removeGlobalListener(listener);
        }
    }

    /**
     * First deployment contains packages yellow and customerApp Second deployment updates the root packages to yellow
     * and red. Red is added, customerApp is removed and no update for yellow
     *
     * @throws Exception
     */
    @Test
    @Order(8)
    void GIVEN_services_running_WHEN_service_added_and_deleted_THEN_add_remove_service_accordingly() throws Exception {

        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("CustomerAppAndYellowSignal.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(10, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        //should contain main, Nucleus, YellowSignal, CustomerApp, Mosquitto and GreenSignal
        assertEquals(6, services.size());
        assertThat(services,
                containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "YellowSignal", "CustomerApp", "Mosquitto",
                        "GreenSignal"));
        groupToRootComponentsTopics.lookupTopics("CustomerApp")
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        groupToRootComponentsTopics.lookupTopics("YellowSignal")
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, ">=1.0.0"));
        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("YellowAndRedSignal.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        //"should contain main, Nucleus, YellowSignal, RedSignal"
        assertEquals(4, services.size());
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "YellowSignal", "RedSignal"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("CustomerApp"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("Mosquitto"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("GreenSignal"));
    }

    /**
     * Start a service running with a user, then deploy an update to change the user and ensure the correct user stops
     * the process and starts the new one.
     */
    @Disabled
    @Test
    @Order(9) // deploy before tests that break services
    void GIVEN_a_deployment_with_runwith_config_WHEN_submitted_THEN_runwith_updated() throws Exception {
        ((Map) kernel.getContext().getvIfExists(Kernel.SERVICE_TYPE_TO_CLASS_MAP_KEY).get())
                .put("plugin", GreengrassService.class.getName());


        countDownLatch = new CountDownLatch(2);
        // Set up stdout listener to capture stdout for verifying users
        List<String> stdouts = new CopyOnWriteArrayList<>();
        Consumer<GreengrassLogMessage> listener = m -> {
            String messageOnStdout = m.getMessage();
            if (messageOnStdout != null && messageOnStdout.contains("with user")) {
                stdouts.add(messageOnStdout);
                countDownLatch.countDown();
            }
        };

        final boolean isWindows = PlatformResolver.isWindows;
        final String currentUser = System.getProperty("user.name");
        final String posixDefaultUser = "nobody";
        final String posixPrivilegedUser = "root";
        final String testServiceName = "CustomerAppStartupShutdown";

        try (AutoCloseable ignored = TestUtils.createCloseableLogListener(listener)) {
            /*
             * 1st deployment. Default Config.
             */
            Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("SampleJobDocumentWithUser_1.json").toURI(),
                    System.currentTimeMillis());
            resultFuture.get(10, TimeUnit.SECONDS);

            // verify configs
            String posixUser = Coerce.toString(kernel.findServiceTopic(testServiceName)
                    .find(RUN_WITH_NAMESPACE_TOPIC, POSIX_USER_KEY));
            String windowsUser = Coerce.toString(kernel.findServiceTopic(testServiceName)
                    .find(RUN_WITH_NAMESPACE_TOPIC, WINDOWS_USER_KEY));
            assertEquals("nobody", posixUser);
            assertEquals(WINDOWS_TEST_UESRNAME, windowsUser);
            long memory = Coerce.toLong(kernel.findServiceTopic(testServiceName)
                    .find(RUN_WITH_NAMESPACE_TOPIC, SYSTEM_RESOURCE_LIMITS_TOPICS, "memory"));
            assertEquals(1024000, memory);
            double cpus = Coerce.toDouble(kernel.findServiceTopic(testServiceName)
                    .find(RUN_WITH_NAMESPACE_TOPIC, SYSTEM_RESOURCE_LIMITS_TOPICS, "cpus"));
            assertEquals(1.5, cpus);

            // verify user
            countDownLatch.await(10, TimeUnit.SECONDS);
            // Install has RequiresPrivilege. On Windows, expect current user is the privileged user
            if (isWindows) {
                assertThat(stdouts, hasItem(containsString("installing app with user " + currentUser)));
                assertThat(stdouts, hasItem(containsString("starting app with user " + WINDOWS_TEST_UESRNAME)));
            } else {
                assertThat(stdouts, hasItem(containsString("installing app with user " + posixPrivilegedUser)));
                assertThat(stdouts, hasItem(containsString("starting app with user " + posixDefaultUser)));
            }
            stdouts.clear();
        }

        /*
         * 2nd deployment. Change user
         */
        countDownLatch = new CountDownLatch(3);

        // update component to runas the user running the test
        String doc = Utils.inputStreamToString(
                DeploymentTaskIntegrationTest.class.getResource("SampleJobDocumentWithUser_2.json").openStream());
        // Set posixUser to currentUser. Set windowsUser to alternative test user
        doc = String.format(doc, currentUser, WINDOWS_TEST_UESRNAME_2);
        File f = File.createTempFile("user-deployment", ".json");
        f.deleteOnExit();
        Files.write(f.toPath(), doc.getBytes(StandardCharsets.UTF_8));
        try (AutoCloseable ignored = TestUtils.createCloseableLogListener(listener)) {
            Future<DeploymentResult> resultFuture = submitSampleJobDocument(f.toURI(), System.currentTimeMillis());
            resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
            String posixUser = Coerce.toString(kernel.findServiceTopic(testServiceName)
                    .find(RUN_WITH_NAMESPACE_TOPIC, POSIX_USER_KEY));
            String windowsUser = Coerce.toString(kernel.findServiceTopic(testServiceName)
                    .find(RUN_WITH_NAMESPACE_TOPIC, WINDOWS_USER_KEY));
            assertEquals(currentUser, posixUser);
            assertEquals(WINDOWS_TEST_UESRNAME_2, windowsUser);

            countDownLatch.await(10, TimeUnit.SECONDS);
            if (isWindows) {
                assertThat(stdouts, hasItem(containsString("stopping app with user " + WINDOWS_TEST_UESRNAME)));
                assertThat(stdouts, hasItem(containsString("installing app with user " + currentUser)));
                assertThat(stdouts, hasItem(containsString("starting app with user " + WINDOWS_TEST_UESRNAME_2)));
            } else {
                assertThat(stdouts, hasItem(containsString("stopping app with user " + posixDefaultUser)));
                assertThat(stdouts, hasItem(containsString("installing app with user " + posixPrivilegedUser)));
                assertThat(stdouts, hasItem(containsString("starting app with user " + currentUser)));
            }
            stdouts.clear();
        }


        /*
         * 3rd deployment. Set runWith user to null and use default
         */
        countDownLatch = new CountDownLatch(3);

        // update component to runas the user running the test
        try (AutoCloseable ignored = TestUtils.createCloseableLogListener(listener)) {
            Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("SampleJobDocumentRemovingUser.json").toURI(),
                    System.currentTimeMillis());
            resultFuture.get(10, TimeUnit.SECONDS);
            String posixUser = Coerce.toString(kernel.findServiceTopic(testServiceName)
                    .find(RUN_WITH_NAMESPACE_TOPIC, POSIX_USER_KEY));
            String windowsUser = Coerce.toString(kernel.findServiceTopic(testServiceName)
                    .find(RUN_WITH_NAMESPACE_TOPIC, WINDOWS_USER_KEY));

            assertThat(posixUser, is(nullValue()));
            assertThat(windowsUser, is(nullValue()));

            // Assert fall back to runWithDefault
            countDownLatch.await(10, TimeUnit.SECONDS);
            if (isWindows) {
                assertThat(stdouts, hasItem(containsString("stopping app with user " + WINDOWS_TEST_UESRNAME_2)));
                assertThat(stdouts, hasItem(containsString("installing app with user " + currentUser)));
                assertThat(stdouts, hasItem(containsString("starting app with user " + WINDOWS_TEST_UESRNAME)));
            } else {
                assertThat(stdouts, hasItem(containsString("stopping app with user " + currentUser)));
                assertThat(stdouts, hasItem(containsString("installing app with user " + posixPrivilegedUser)));
                assertThat(stdouts, hasItem(containsString("starting app with user " + posixDefaultUser)));
            }
        }
    }

    /**
     * First deployment starts some services. Second deployment tries to add a service that breaks and removes an
     * existing service but the failure handling policy is to do nothing As a result, no corrective action will be taken
     * on failure
     *
     * @throws Exception
     */
    @Test
    @Order(10)
    void GIVEN_services_running_WHEN_new_service_breaks_failure_handling_policy_do_nothing_THEN_service_stays_broken(
            ExtensionContext context) throws Exception {
        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("YellowAndRedSignal.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        // should contain main, Nucleus, YellowSignal and RedSignal
        assertEquals(4, services.size());
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "YellowSignal", "RedSignal"));
        groupToRootComponentsTopics.lookupTopics("RedSignal")
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        groupToRootComponentsTopics.lookupTopics("YellowSignal")
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        ignoreExceptionUltimateCauseOfType(context, ServiceUpdateException.class);

        preloadLocalStoreContent();
        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("FailureDoNothingDeployment.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        // should contain main, Nucleus, RedSignal, BreakingService, Mosquitto and GreenSignal
        assertEquals(6, services.size());
        assertThat(services,
                containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "RedSignal", "BreakingService", "Mosquitto",
                        "GreenSignal"));
        assertEquals(State.BROKEN, kernel.locate("BreakingService").getState());
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED, result.getDeploymentStatus());
    }

    /**
     * First deployment starts some services. Second deployment tries to add a service that breaks and removes an
     * existing service and the failure handling policy is to rollback As a result, kernel should be reverted to the
     * state before deployment
     *
     * @throws Exception
     */
    @Test
    @Order(11)
    void GIVEN_services_running_WHEN_new_service_breaks_failure_handling_policy_rollback_THEN_services_are_rolled_back(
            ExtensionContext context) throws Exception {
        Map<String, Object> pkgDetails = new HashMap<>();
        pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0");
        groupToRootComponentsTopics.lookupTopics("RedSignal").replaceAndWait(pkgDetails);
        groupToRootComponentsTopics.lookupTopics("YellowSignal").replaceAndWait(pkgDetails);
        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("YellowAndRedSignal.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        // should contain main, Nucleus, YellowSignal and RedSignal
        assertEquals(4, services.size());
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "YellowSignal", "RedSignal"));

        ignoreExceptionUltimateCauseOfType(context, ServiceUpdateException.class);
        groupToRootComponentsTopics.lookupTopics("YellowSignal").remove();
        groupToRootComponentsTopics.lookupTopics("BreakingService")
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));

        preloadLocalStoreContent();
        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("FailureRollbackDeployment.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(60, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        // should contain main, Nucleus, YellowSignal, RedSignal
        assertEquals(4, services.size());
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "YellowSignal", "RedSignal"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("BreakingService"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("Mosquitto"));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("GreenSignal"));
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, result.getDeploymentStatus());
    }

    /**
     * This test verifies that if a deployment has a broken service and then a new deployment comes which removes that
     * one, but fails for a different reason and rolls back, then it is able to roll back successfully.
     */
    @Test
    @Order(12)
    void GIVEN_broken_service_WHEN_new_service_breaks_failure_handling_policy_rollback_THEN_services_are_rolled_back(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ServiceUpdateException.class);

        // Deploy a broken config with no rollback
        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("FailureDoNothingDeployment.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(60, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "BreakingService", "RedSignal",
                "GreenSignal", "Mosquitto"));

        // Deploy a new broken config (using a different service) which does rollback
        preloadLocalStoreContent();
        resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("Failure2RollbackDeployment.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(60, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        // Make sure that it rolls back to the previous state
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "BreakingService", "RedSignal",
                "GreenSignal", "Mosquitto"));
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, result.getDeploymentStatus());
    }

    @Test
    @Order(99)
    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    void GIVEN_deployment_in_progress_WHEN_deployment_task_is_cancelled_THEN_stop_processing() throws Exception {
        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("AddNewServiceWithSafetyCheck.json").toURI(),
                System.currentTimeMillis());
        resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);

        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "NonDisruptableService");
        final EventStreamRPCConnection clientConnection =
                IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);
        SubscribeToComponentUpdatesRequest subscribeToComponentUpdatesRequest =
                new SubscribeToComponentUpdatesRequest();
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        CompletableFuture<SubscribeToComponentUpdatesResponse> fut = greengrassCoreIPCClient
                .subscribeToComponentUpdates(subscribeToComponentUpdatesRequest,
                        Optional.of(new StreamResponseHandler<ComponentUpdatePolicyEvents>() {
                            @Override
                            public void onStreamEvent(ComponentUpdatePolicyEvents streamEvent) {
                                if (streamEvent.getPreUpdateEvent() != null) {
                                    DeferComponentUpdateRequest deferComponentUpdateRequest =
                                            new DeferComponentUpdateRequest();
                                    deferComponentUpdateRequest.setRecheckAfterMs(Duration.ofSeconds(60).toMillis());
                                    deferComponentUpdateRequest.setMessage("Test");
                                    deferComponentUpdateRequest
                                            .setDeploymentId(streamEvent.getPreUpdateEvent().getDeploymentId());
                                    greengrassCoreIPCClient
                                            .deferComponentUpdate(deferComponentUpdateRequest, Optional.empty());
                                }
                            }

                            @Override
                            public boolean onStreamError(Throwable error) {
                                logger.atError().setCause(error).log("Stream closed due to error");
                                return false;
                            }

                            @Override
                            public void onStreamClosed() {

                            }
                        })).getResponse();
        try {
            fut.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.atError().setCause(e).log("Error when subscribing to component updates");
            fail("Caught exception when subscribing to component updates");
        }
        List<String> services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        // should contain main, Nucleus, NonDisruptableService 1.0.0
        assertEquals(3, services.size(), "Actual services: " + services);
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "NonDisruptableService"));

        CountDownLatch cdlUpdateStarted = new CountDownLatch(1);
        CountDownLatch cdlMergeCancelled = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {
            if (m.getMessage() != null && m.getMessage().contains("deferred for 60000 millis with message Test")) {
                cdlUpdateStarted.countDown();
            }
            if (m.getMessage() != null && m.getMessage()
                    .contains("Cancelled deployment merge future due to interrupt")) {
                cdlMergeCancelled.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);
        try {
            resultFuture = submitSampleJobDocument(
                    DeploymentTaskIntegrationTest.class.getResource("UpdateServiceWithSafetyCheck.json").toURI(),
                    System.currentTimeMillis());

            assertTrue(cdlUpdateStarted.await(40, TimeUnit.SECONDS));
            resultFuture.cancel(true);

            assertTrue(cdlMergeCancelled.await(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS));

            services = kernel.orderedDependencies().stream()
                    .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                    .map(GreengrassService::getName).collect(Collectors.toList());

            // should contain main, Nucleus, NonDisruptableService 1.0.0
            assertEquals(3, services.size());
            assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "NonDisruptableService"));
            assertEquals("1.0.0", kernel.findServiceTopic("NonDisruptableService").find("version").getOnce());
        } finally {
            Slf4jLogAdapter.removeGlobalListener(listener);
            clientConnection.close();
        }
    }

    @Test
    @Order(100)
    void GIVEN_services_running_WHEN_new_deployment_asks_to_skip_update_policy_check_THEN_deployment_is_successful()
            throws Exception {
        // The previous test has NonDisruptableService 1.0.0 running in kernel that always returns false when its
        // update policy check is run, this test demonstrates that when a next deployment configured to skip update policy
        // check is processed, it can still update the NonDisruptableService service to version 1.0.1 bypassing the
        // update policy check
        Future<DeploymentResult> resultFuture =
                submitSampleJobDocument(DeploymentTaskIntegrationTest.class.getResource("SkipPolicyCheck.json").toURI(),
                        System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        // should contain main, Nucleus, NonDisruptableService 1.0.1
        assertEquals(3, services.size(), "Existing services: " + services);
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME, "NonDisruptableService"));
        assertEquals("1.0.1", kernel.findServiceTopic("NonDisruptableService").find("version").getOnce());
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
    }

    @Test
    @Order(101)
    void GIVEN_services_with_dependency_broken_WHEN_new_deployment_failure_handling_policy_rollback_THEN_deployment_rollback_is_successful(
            ExtensionContext context) throws Exception {

        ignoreExceptionUltimateCauseOfType(context, ServiceUpdateException.class);

        // Deploy a broken config with no rollback
        Future<DeploymentResult> resultFuture = submitSampleJobDocument(
                DeploymentTaskIntegrationTest.class.getResource("DependencyDoNothing.json").toURI(),
                System.currentTimeMillis());
        DeploymentResult result = resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        resultFuture.get(60, TimeUnit.SECONDS);
        List<String> services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());
        // should contain main, Nucleus, BreakingService2, DependencyOnBreak
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME,
                "BreakingService2", "DependencyOnBreak", "DependencyOnDependency", "SoftDependencyOnBreak"));
        assertEquals(State.BROKEN, kernel.locate("BreakingService2").getState());
        assertEquals("1.0.0", kernel.findServiceTopic("DependencyOnBreak").find("version").getOnce());
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED, result.getDeploymentStatus());
        preloadLocalStoreContent();

        // Deploy a new config, increase depender version, with rollback.
        resultFuture =
                submitSampleJobDocument(DeploymentTaskIntegrationTest.class.getResource("DependencyRollback.json").toURI(),
                        System.currentTimeMillis());
        result = resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        assertEquals("1.0.0", kernel.findServiceTopic("DependencyOnBreak").find("version").getOnce());
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, result.getDeploymentStatus());

        //Check if an empty deployment would remove all components
        resultFuture =
                submitSampleJobDocument(DeploymentTaskIntegrationTest.class.getResource("EmptyDeployment.json").toURI(),
                        System.currentTimeMillis());
        result = resultFuture.get(DEPLOYMENT_TIMEOUT, TimeUnit.SECONDS);
        services = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
        assertEquals(2, services.size(), "Existing services: " + services);
        assertThat(services, containsInAnyOrder("main", DEFAULT_NUCLEUS_COMPONENT_NAME));
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private Future<DeploymentResult> submitSampleJobDocument(URI uri, Long timestamp) throws Exception {
        kernel.getContext().get(DeploymentDirectoryManager.class)
                .createNewDeploymentDirectory("testFleetConfigArn" + deploymentCount.getAndIncrement());

        sampleJobDocument = OBJECT_MAPPER.readValue(new File(uri), DeploymentDocument.class);
        sampleJobDocument.setTimestamp(timestamp);
        sampleJobDocument.setGroupName(MOCK_GROUP_NAME);
        DefaultDeploymentTask deploymentTask =
                new DefaultDeploymentTask(dependencyResolver, componentManager, kernelConfigResolver,
                        deploymentConfigMerger, logger,
                        new Deployment(sampleJobDocument, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT),
                        deploymentServiceTopics, kernel.getContext().get(ExecutorService.class), deploymentDocumentDownloader, thingGroupHelper, kernel.getContext().get(DeviceConfiguration.class));
        return executorService.submit(deploymentTask);
    }
}
