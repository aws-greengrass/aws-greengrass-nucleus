/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.amazon.aws.iot.greengrass.component.common.SerializerFactory;
import com.amazon.aws.iot.greengrass.component.common.Unarchive;
import com.aws.greengrass.componentmanager.builtins.ArtifactDownloader;
import com.aws.greengrass.componentmanager.builtins.ArtifactDownloaderFactory;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.exceptions.MissingRequiredComponentsException;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.exceptions.SizeLimitException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.componentmanager.models.RecipeMetadata;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.exceptions.RetryableServerErrorException;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Digest;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.RetryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.internal.util.collections.Sets;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.greengrassv2data.model.ResolvedComponentVersion;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PREV_VERSION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.COMPONENT_STORE_MAX_SIZE_BYTES;
import static com.aws.greengrass.deployment.DeviceConfiguration.COMPONENT_STORE_MAX_SIZE_DEFAULT_BYTES;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({
        GGExtension.class, MockitoExtension.class
})
class ComponentManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_ARN = "testArn";
    private static final String MONITORING_SERVICE_PKG_NAME = "MonitoringService";
    private static final String ACTIVE_VERSION_STR = "2.0.0";
    private static final Semver ACTIVE_VERSION = new Semver(ACTIVE_VERSION_STR);
    private static final Semver v1_2_0 = new Semver("1.2.0");
    private static final Semver v1_0_0 = new Semver("1.0.0");
    private static final String componentA = "A";
    private static final long TEN_TERA_BYTES = 10_000_000_000_000L;
    private static final long TEN_BYTES = 10L;
    private static Path RECIPE_RESOURCE_PATH;

    static {
        try {
            RECIPE_RESOURCE_PATH = Paths.get(ComponentManagerTest.class.getResource("recipes").toURI());
        } catch (URISyntaxException ignore) {
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    @TempDir
    Path tempDir;
    private ComponentManager componentManager;
    private RecipeLoader recipeLoader;
    @Mock
    private ArtifactDownloader artifactDownloader;
    @Mock
    private ArtifactDownloaderFactory artifactDownloaderFactory;
    @Mock
    private ComponentServiceHelper componentManagementServiceHelper;
    @Mock
    private Kernel kernel;
    @Mock
    private Context context;
    @Mock
    private ComponentStore componentStore;
    @Mock
    private GreengrassService mockService;
    @Mock
    private Unarchiver mockUnarchiver;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private NucleusPaths nucleusPaths;

    @BeforeEach
    void beforeEach() throws Exception {
        PlatformResolver platformResolver = new PlatformResolver(null);
        recipeLoader = new RecipeLoader(platformResolver);

        lenient().when(artifactDownloader.downloadRequired()).thenReturn(true);
        lenient().when(artifactDownloader.checkDownloadable()).thenReturn(Optional.empty());
        lenient().when(artifactDownloader.checkComponentStoreSize()).thenReturn(true);
        lenient().when(artifactDownloader.canSetFilePermissions()).thenReturn(true);
        lenient().when(artifactDownloader.canUnarchiveArtifact()).thenReturn(true);
        lenient().when(artifactDownloaderFactory.getArtifactDownloader(any(), any(), any()))
                .thenReturn(artifactDownloader);
        lenient().when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);
        Topic maxSizeTopic = Topic.of(context, COMPONENT_STORE_MAX_SIZE_BYTES, COMPONENT_STORE_MAX_SIZE_DEFAULT_BYTES);
        lenient().when(deviceConfiguration.getComponentStoreMaxSizeBytes()).thenReturn(maxSizeTopic);
        Topic regionTopic = Topic.of(context, DeviceConfiguration.DEVICE_PARAM_AWS_REGION, "us-east-1");
        lenient().when(deviceConfiguration.getAWSRegion()).thenReturn(regionTopic);
        lenient().when(componentStore.getUsableSpace()).thenReturn(100_000_000L);
        componentManager = new ComponentManager(artifactDownloaderFactory, componentManagementServiceHelper, executor,
                componentStore, kernel, mockUnarchiver, deviceConfiguration, nucleusPaths);
    }

    @AfterEach
    void after() throws Exception {
        executor.shutdownNow();
        if (context != null) {
            context.close();
        }
    }

    @Test
    void GIVEN_artifact_list_empty_WHEN_attempt_download_artifact_THEN_do_nothing() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));

        componentManager.prepareArtifacts(pkgId, Collections.emptyList());

        verify(artifactDownloader, never()).download();
    }

    @Test
    void GIVEN_artifact_already_downloaded_WHEN_attempt_download_artifact_THEN_do_not_download() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));

        lenient().when(artifactDownloader.downloadRequired()).thenReturn(false);

        componentManager.prepareArtifacts(pkgId, Collections.emptyList());

        verify(artifactDownloader, never()).download();
    }

    @Test
    void GIVEN_artifact_from_gg_repo_WHEN_download_artifact_with_unarchive_THEN_calls_unarchiver() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));

        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);
        when(artifactDownloader.download()).thenReturn(new File("binary1"));
        when(artifactDownloader.getArtifactFile()).thenReturn(new File("binary1"));

        componentManager.prepareArtifacts(pkgId, Arrays.asList(
                ComponentArtifact.builder().artifactUri(new URI("greengrass:binary1")).unarchive(Unarchive.ZIP).build(),
                ComponentArtifact.builder()
                        .artifactUri(new URI("greengrass:binary2"))
                        .unarchive(Unarchive.NONE)
                        .build()));

        ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
        verify(mockUnarchiver).unarchive(any(), fileCaptor.capture(), any());
        assertEquals("binary1", fileCaptor.getValue().getName());
    }

    @Test
    void GIVEN_package_identifier_WHEN_request_to_prepare_package_THEN_task_succeed() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("MonitoringService", new Semver("1.0.0"));
        String fileName = "MonitoringService-1.0.0.yaml";
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);

        String sourceRecipeString = new String(Files.readAllBytes(sourceRecipe));
        ComponentRecipe componentRecipe = recipeLoader.loadFromFile(sourceRecipeString).get();

        when(componentStore.getPackageRecipe(pkgId)).thenReturn(componentRecipe);
        Future<Void> future = componentManager.preparePackages(Collections.singletonList(pkgId));
        future.get(5, TimeUnit.SECONDS);

        assertThat(future.isDone(), is(true));

        verify(componentStore).getPackageRecipe(pkgId);
        verifyNoMoreInteractions(componentStore);

    }

    @Test
    void GIVEN_package_service_error_out_WHEN_request_to_prepare_package_THEN_task_error_out(ExtensionContext context)
            throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("SomeService", new Semver("1.0.0"));
        ignoreExceptionUltimateCauseOfType(context, PackageDownloadException.class);

        Future<Void> future = componentManager.preparePackages(Collections.singletonList(pkgId));
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_prepare_packages_running_WHEN_prepare_cancelled_THEN_task_stops() throws Exception {
        ComponentIdentifier pkgId1 = new ComponentIdentifier("MonitoringService", new Semver("1.0.0"));
        ComponentIdentifier pkgId2 = new ComponentIdentifier("CoolService", new Semver("1.0.0"));

        String fileName = "MonitoringService-1.0.0.yaml";
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);
        ComponentRecipe pkg1 = recipeLoader.loadFromFile(new String(Files.readAllBytes(sourceRecipe))).get();

        CountDownLatch startedPreparingPkgId1 = new CountDownLatch(1);
        when(componentStore.getPackageRecipe(pkgId1)).thenAnswer(invocationOnMock -> {
            startedPreparingPkgId1.countDown();
            Thread.sleep(2_000);
            return pkg1;
        });

        Future<Void> future = componentManager.preparePackages(Arrays.asList(pkgId1, pkgId2));
        assertTrue(startedPreparingPkgId1.await(1, TimeUnit.SECONDS));
        future.cancel(true);

        verify(componentStore).getPackageRecipe(pkgId1);
        verify(componentStore, times(0)).getPackageRecipe(pkgId2);
    }

    @Test
    void GIVEN_service_has_version_WHEN_getPackageVersionFromService_THEN_returnIt() {
        Topics serviceConfigTopics = mock(Topics.class);
        Topic versionTopic = mock(Topic.class);

        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(ACTIVE_VERSION_STR);

        assertThat(componentManager.getPackageVersionFromService(mockService), is(ACTIVE_VERSION));
    }

    @Test
    void GIVEN_requirement_is_from_local_group_and_has_local_version_WHEN_resolve_version_THEN_use_local_version()
            throws Exception {
        ComponentIdentifier componentA_1_2_0 = new ComponentIdentifier(componentA, v1_2_0);
        ComponentMetadata componentA_1_2_0_md = new ComponentMetadata(componentA_1_2_0, Collections.emptyMap());
        when(componentStore.findBestMatchAvailableComponent(eq(componentA), any()))
                .thenReturn(Optional.of(componentA_1_2_0));
        when(componentStore.getPackageMetadata(any())).thenReturn(componentA_1_2_0_md);
        when(componentStore.componentMetadataRegionCheck(componentA_1_2_0, "us-east-1")).thenReturn(true);

        ComponentMetadata componentMetadata = componentManager.resolveComponentVersion(componentA, Collections
                .singletonMap(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME, Requirement.buildNPM("^1.0")));

        assertThat(componentMetadata, is(componentA_1_2_0_md));
        verify(componentStore).findBestMatchAvailableComponent(componentA, Requirement.buildNPM("^1.0"));
        verify(componentStore).getPackageMetadata(componentA_1_2_0);
        verify(componentManagementServiceHelper, never()).resolveComponentVersion(anyString(), any(), any());
    }

    @Test
    void GIVEN_locally_installed_component_WHEN_invalid_recipe_metadata_THEN_use_cloud_version() throws Exception {
        // has local version
        ComponentIdentifier componentA_1_2_0 = new ComponentIdentifier(componentA, v1_2_0);
        when(componentStore.findBestMatchAvailableComponent(eq(componentA), any()))
                .thenReturn(Optional.of(componentA_1_2_0));

        // has cloud version
        ComponentIdentifier componentA_1_0_0 = new ComponentIdentifier(componentA, v1_0_0);
        ComponentMetadata componentA_1_0_0_md = new ComponentMetadata(componentA_1_0_0, Collections.emptyMap());
        when(componentStore.getPackageMetadata(componentA_1_0_0)).thenReturn(componentA_1_0_0_md);
        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe recipe =
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName(componentA)
                        .componentVersion(v1_0_0)
                        .componentType(ComponentType.GENERIC)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                        .build();

        ResolvedComponentVersion resolvedComponentVersion = ResolvedComponentVersion.builder()
                .componentName(componentA)
                .componentVersion(v1_0_0.getValue())
                .recipe(SdkBytes.fromByteArray(MAPPER.writeValueAsBytes(recipe)))
                .arn(TEST_ARN)
                .build();

        when(componentManagementServiceHelper.resolveComponentVersion(anyString(), any(), any()))
                .thenReturn(resolvedComponentVersion);

        // local recipe metadata invalid
        when(componentStore.componentMetadataRegionCheck(componentA_1_2_0, "us-east-1")).thenReturn(false);

        // resolve cloud instead of local
        ComponentMetadata componentMetadata = componentManager.resolveComponentVersion(componentA, Collections
                .singletonMap(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME, Requirement.buildNPM("^1.0")));

        assertThat(componentMetadata, is(componentA_1_0_0_md));
        verify(componentStore).findBestMatchAvailableComponent(componentA, Requirement.buildNPM("^1.0"));
        verify(componentStore).getPackageMetadata(componentA_1_0_0);
        verify(componentStore).saveComponentRecipe(recipe);
        verify(componentStore).saveRecipeMetadata(componentA_1_0_0, new RecipeMetadata(TEST_ARN));
    }

    @Test
    void GIVEN_requirement_is_from_local_group_and_has_no_local_version_WHEN_resolve_version_THEN_use_cloud_version()
            throws Exception {
        ComponentIdentifier componentA_1_0_0 = new ComponentIdentifier(componentA, v1_0_0);
        ComponentMetadata componentA_1_0_0_md = new ComponentMetadata(componentA_1_0_0, Collections.emptyMap());

        // no local version
        when(componentStore.findBestMatchAvailableComponent(eq(componentA), any())).thenReturn(Optional.empty());

        // has cloud version
        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe recipe =
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName(componentA)
                        .componentVersion(v1_0_0)
                        .componentType(ComponentType.GENERIC)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                        .build();

        ResolvedComponentVersion resolvedComponentVersion = ResolvedComponentVersion.builder()
                .componentName(componentA)
                .componentVersion(v1_0_0.getValue())
                .recipe(SdkBytes.fromByteArray(MAPPER.writeValueAsBytes(recipe)))
                .arn(TEST_ARN)
                .build();

        when(componentManagementServiceHelper.resolveComponentVersion(anyString(), any(), any()))
                .thenReturn(resolvedComponentVersion);
        // mock return metadata from the id
        when(componentStore.getPackageMetadata(any())).thenReturn(componentA_1_0_0_md);

        // WHEN
        ComponentMetadata componentMetadata = componentManager.resolveComponentVersion(componentA, Collections
                .singletonMap(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME, Requirement.buildNPM("^1.0")));

        // THEN
        assertThat(componentMetadata, is(componentA_1_0_0_md));

        verify(componentStore).findBestMatchAvailableComponent(componentA, Requirement.buildNPM("^1.0"));
        verify(componentManagementServiceHelper).resolveComponentVersion(componentA, null, Collections
                .singletonMap(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME, Requirement.buildNPM("^1.0")));
        verify(componentStore).getPackageMetadata(componentA_1_0_0);
        verify(componentStore).saveComponentRecipe(recipe);
        verify(componentStore).saveRecipeMetadata(componentA_1_0_0, new RecipeMetadata(TEST_ARN));
    }

    @Test
    void GIVEN_component_is_local_active_WHEN_cloud_returns_a_recipe_THEN_use_cloud_recipe() throws Exception {
        ComponentIdentifier componentA_1_0_0 = new ComponentIdentifier(componentA, v1_0_0);
        ComponentMetadata componentA_1_0_0_md = new ComponentMetadata(componentA_1_0_0, Collections.emptyMap());
        Topics serviceConfigTopics = mock(Topics.class);
        Topic versionTopic = mock(Topic.class);
        Topics runtimeTopics = mock(Topics.class);
        Topic digestTopic = mock(Topic.class);

        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe newRecipe =
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName("SampleComponent2")
                        .componentVersion(new Semver("2.0.0"))
                        .componentType(ComponentType.PLUGIN)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                        .build();

        GreengrassService mockKernelService = mock(GreengrassService.class);
        when(kernel.findServiceTopic(componentA)).thenReturn(mock(Topics.class));
        when(kernel.locate(componentA)).thenReturn(mockService);
        when(kernel.getMain()).thenReturn(mockKernelService);
        when(mockKernelService.getRuntimeConfig()).thenReturn(runtimeTopics);
        when(runtimeTopics.lookup(any())).thenReturn(digestTopic);
        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(v1_0_0.getValue());

        ResolvedComponentVersion resolvedComponentVersion = ResolvedComponentVersion.builder()
                .componentName(componentA)
                .componentVersion(v1_0_0.getValue())
                .recipe(SdkBytes.fromByteArray(MAPPER.writeValueAsBytes(newRecipe)))
                .arn(TEST_ARN)
                .build();
        when(componentManagementServiceHelper.resolveComponentVersion(anyString(), any(), any()))
                .thenReturn(resolvedComponentVersion);
        when(componentStore.getPackageMetadata(any())).thenReturn(componentA_1_0_0_md);

        String recipeString = SerializerFactory.getRecipeSerializer().writeValueAsString(newRecipe);

        when(componentStore.saveComponentRecipe(any())).thenReturn(recipeString);

        ComponentMetadata componentMetadata = componentManager.resolveComponentVersion(componentA,
                Collections.singletonMap("X", Requirement.buildNPM("^1.0")));

        assertThat(componentMetadata, is(componentA_1_0_0_md));
        verify(componentManagementServiceHelper).resolveComponentVersion(componentA, v1_0_0,
                Collections.singletonMap("X", Requirement.buildNPM("^1.0")));
        verify(componentStore).saveComponentRecipe(newRecipe);
        verify(componentStore).getPackageMetadata(componentA_1_0_0);
        verify(componentStore).saveRecipeMetadata(componentA_1_0_0, new RecipeMetadata(TEST_ARN));
        verify(digestTopic).withValue(Digest.calculate(recipeString));
    }

    @Test
    void GIVEN_component_is_builtin_service_WHEN_cloud_service_exception_THEN_resolve_to_local_version(
            ExtensionContext context) throws Exception {

        ComponentIdentifier componentA_1_0_0 = new ComponentIdentifier(componentA, v1_0_0);
        ComponentMetadata componentA_1_0_0_md = new ComponentMetadata(componentA_1_0_0, Collections.emptyMap());

        Topics serviceConfigTopics = mock(Topics.class);
        Topic versionTopic = mock(Topic.class);

        when(kernel.findServiceTopic(componentA)).thenReturn(mock(Topics.class));
        when(kernel.locate(componentA)).thenReturn(mockService);
        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(v1_0_0.getValue());
        when(mockService.isBuiltin()).thenReturn(true);

        when(componentManagementServiceHelper.resolveComponentVersion(anyString(), any(), any()))
                .thenThrow(NoAvailableComponentVersionException.class);
        when(componentStore.getPackageMetadata(any())).thenThrow(PackagingException.class);

        ignoreExceptionOfType(context, NoAvailableComponentVersionException.class);

        ComponentMetadata componentMetadata = componentManager.resolveComponentVersion(componentA,
                Collections.singletonMap("X", Requirement.buildNPM("^1.0")));

        assertThat(componentMetadata, is(componentA_1_0_0_md));
        verify(componentStore, never()).findComponentRecipeContent(any());
        verify(componentStore, never()).saveComponentRecipe(any());
        verify(componentStore).getPackageMetadata(componentA_1_0_0);
    }

    @Test
    void GIVEN_component_no_local_version_WHEN_cloud_service_client_exception_THEN_retry(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, SdkClientException.class);

        componentManager.setClientExceptionRetryConfig(RetryUtils.RetryConfig.builder()
                .initialRetryInterval(Duration.ofSeconds(1))
                .maxRetryInterval(Duration.ofSeconds(1))
                .maxAttempt(Integer.MAX_VALUE)
                .retryableExceptions(Arrays.asList(SdkClientException.class))
                .build());

        ComponentIdentifier componentA_1_0_0 = new ComponentIdentifier(componentA, v1_0_0);
        ComponentMetadata componentA_1_0_0_md = new ComponentMetadata(componentA_1_0_0, Collections.emptyMap());

        // no local version
        when(componentStore.findBestMatchAvailableComponent(eq(componentA), any())).thenReturn(Optional.empty());

        // has cloud version
        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe recipe =
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName(componentA)
                        .componentVersion(v1_0_0)
                        .componentType(ComponentType.GENERIC)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                        .build();

        ResolvedComponentVersion resolvedComponentVersion = ResolvedComponentVersion.builder()
                .componentName(componentA)
                .componentVersion(v1_0_0.getValue())
                .recipe(SdkBytes.fromByteArray(MAPPER.writeValueAsBytes(recipe)))
                .arn(TEST_ARN)
                .build();

        // Retry succeeds
        when(componentManagementServiceHelper.resolveComponentVersion(anyString(), any(), any()))
                .thenThrow(SdkClientException.class)
                .thenReturn(resolvedComponentVersion);
        // mock return metadata from the id
        when(componentStore.getPackageMetadata(any())).thenReturn(componentA_1_0_0_md);

        ComponentMetadata componentMetadata = componentManager.resolveComponentVersion(componentA,
                Collections.singletonMap("X", Requirement.buildNPM("^1.0")));

        assertThat(componentMetadata, is(componentA_1_0_0_md));
        verify(componentManagementServiceHelper, times(2)).resolveComponentVersion(componentA, null,
                Collections.singletonMap("X", Requirement.buildNPM("^1.0")));
        verify(componentStore, never()).findComponentRecipeContent(any());
        verify(componentStore).saveComponentRecipe(any());
        verify(componentStore).getPackageMetadata(componentA_1_0_0);
    }

    @Test
    void GIVEN_component_no_local_version_WHEN_cloud_deployment_exception_THEN_retry(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, RetryableServerErrorException.class);

        componentManager.setClientExceptionRetryConfig(RetryUtils.RetryConfig.builder()
                .initialRetryInterval(Duration.ofSeconds(1))
                .maxRetryInterval(Duration.ofSeconds(1))
                .maxAttempt(Integer.MAX_VALUE)
                .retryableExceptions(Arrays.asList(RetryableServerErrorException.class))
                .build());

        ComponentIdentifier componentA_1_0_0 = new ComponentIdentifier(componentA, v1_0_0);
        ComponentMetadata componentA_1_0_0_md = new ComponentMetadata(componentA_1_0_0, Collections.emptyMap());

        // no local version
        when(componentStore.findBestMatchAvailableComponent(eq(componentA), any())).thenReturn(Optional.empty());

        // has cloud version and trigger negotiatetoCould
        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe recipe =
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName(componentA)
                        .componentVersion(v1_0_0)
                        .componentType(ComponentType.GENERIC)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                        .build();

        ResolvedComponentVersion resolvedComponentVersion = ResolvedComponentVersion.builder()
                .componentName(componentA)
                .componentVersion(v1_0_0.getValue())
                .recipe(SdkBytes.fromByteArray(MAPPER.writeValueAsBytes(recipe)))
                .arn(TEST_ARN)
                .build();

        // Retry succeeds
        when(componentManagementServiceHelper.resolveComponentVersion(anyString(), any(), any()))
                .thenThrow(RetryableServerErrorException.class)
                .thenReturn(resolvedComponentVersion);
        // mock return metadata from the id
        when(componentStore.getPackageMetadata(any())).thenReturn(componentA_1_0_0_md);

        ComponentMetadata componentMetadata = componentManager.resolveComponentVersion(componentA,
                Collections.singletonMap("X", Requirement.buildNPM("^1.0")));

        assertThat(componentMetadata, is(componentA_1_0_0_md));
        verify(componentManagementServiceHelper, times(2)).resolveComponentVersion(componentA, null,
                Collections.singletonMap("X", Requirement.buildNPM("^1.0")));
        verify(componentStore, never()).findComponentRecipeContent(any());
        verify(componentStore).saveComponentRecipe(any());
        verify(componentStore).getPackageMetadata(componentA_1_0_0);
    }

    @Test
    void GIVEN_component_WHEN_disk_space_critical_and_prepare_components_THEN_throws_exception(ExtensionContext context)
            throws Exception {
        // mock get recipe
        ComponentIdentifier pkgId = new ComponentIdentifier("SimpleApp", new Semver("1.0.0"));
        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);
        String fileName = "SimpleApp-1.0.0.yaml";
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);
        String sourceRecipeString = new String(Files.readAllBytes(sourceRecipe));
        ComponentRecipe componentRecipe = recipeLoader.loadFromFile(sourceRecipeString).get();
        when(componentStore.getPackageRecipe(pkgId)).thenReturn(componentRecipe);

        // mock very limited space left
        when(componentStore.getUsableSpace()).thenReturn(TEN_BYTES);

        ignoreExceptionUltimateCauseOfType(context, SizeLimitException.class);
        Future<Void> future = componentManager.preparePackages(Collections.singletonList(pkgId));
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        verify(artifactDownloader, never()).download();
    }

    @Test
    void GIVEN_component_WHEN_component_store_full_and_prepare_components_THEN_throws_exception(
            ExtensionContext context) throws Exception {
        // mock get recipe
        ComponentIdentifier pkgId = new ComponentIdentifier("SimpleApp", new Semver("1.0.0"));
        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);
        String fileName = "SimpleApp-1.0.0.yaml";
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);
        String sourceRecipeString = new String(Files.readAllBytes(sourceRecipe));
        ComponentRecipe componentRecipe = recipeLoader.loadFromFile(sourceRecipeString).get();
        when(componentStore.getPackageRecipe(pkgId)).thenReturn(componentRecipe);

        // mock very large component store size
        when(componentStore.getContentSize()).thenReturn(TEN_TERA_BYTES);
        when(artifactDownloader.getDownloadSize()).thenReturn(TEN_BYTES);

        ignoreExceptionUltimateCauseOfType(context, SizeLimitException.class);
        Future<Void> future = componentManager.preparePackages(Collections.singletonList(pkgId));
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        verify(artifactDownloader, never()).download();
    }

    @Test
    void GIVEN_kernel_service_configs_WHEN_get_versions_to_keep_THEN_return_correct_result() {
        Collection<GreengrassService> mockOrderedDeps =
                Collections.singletonList(getMockGreengrassService(MONITORING_SERVICE_PKG_NAME));
        when(kernel.orderedDependencies()).thenReturn(mockOrderedDeps);

        // WHEN
        Map<String, Set<String>> versionsToKeep = componentManager.getVersionsToKeep();

        Map<String, Set<String>> expectedResult = new HashMap<>();
        expectedResult.put(MONITORING_SERVICE_PKG_NAME, Sets.newSet("1.0.0", "2.0.0"));
        assertEquals(expectedResult, versionsToKeep);
    }

    @Test
    void GIVEN_stale_artifact_exists_WHEN_cleanup_THEN_delete_component_invoked_correctly() throws Exception {
        // mock service configs has version 1 and 2
        Collection<GreengrassService> mockOrderedDeps =
                Collections.singletonList(getMockGreengrassService(MONITORING_SERVICE_PKG_NAME));
        when(kernel.orderedDependencies()).thenReturn(mockOrderedDeps);

        GreengrassService mockKernelService = mock(GreengrassService.class);
        Topics runtimeTopics = mock(Topics.class);
        Topic digestTopic = mock(Topic.class);
        when(kernel.getMain()).thenReturn(mockKernelService);
        when(mockKernelService.getRuntimeConfig()).thenReturn(runtimeTopics);
        ArgumentCaptor<String> identifierCaptor = ArgumentCaptor.forClass(String.class);
        when(runtimeTopics.find(any(), identifierCaptor.capture())).thenReturn(digestTopic);

        // mock local artifacts with version 1, 2, 3 and another component
        String anotherCompName = "SimpleApp";
        Map<String, Set<String>> mockArtifacts = new HashMap<>();
        mockArtifacts.put(MONITORING_SERVICE_PKG_NAME, Sets.newSet("1.0.0", "2.0.0", "3.0.0"));
        mockArtifacts.put(anotherCompName, Sets.newSet("1.0.0", "2.0.0"));
        when(componentStore.listAvailableComponentVersions()).thenReturn(mockArtifacts);

        // WHEN
        componentManager.cleanupStaleVersions();

        // THEN
        verify(componentStore, times(1)).deleteComponent(
                new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("3.0.0")), artifactDownloaderFactory);
        verify(componentStore, times(1)).deleteComponent(new ComponentIdentifier(anotherCompName, new Semver("1.0.0")),
                artifactDownloaderFactory);
        verify(componentStore, times(1)).deleteComponent(new ComponentIdentifier(anotherCompName, new Semver("2.0.0")),
                artifactDownloaderFactory);

        // verify digest was cleaned up
        verify(digestTopic, times(3)).remove();
        assertThat(identifierCaptor.getAllValues(), containsInAnyOrder(MONITORING_SERVICE_PKG_NAME + "-v3.0.0",
                anotherCompName + "-v1.0.0", anotherCompName + "-v2.0.0"));
    }

    @Test
    void GIVEN_deployment_WHEN_dependency_closure_has_download_prereq_component_THEN_succeed() throws Exception {
        ComponentIdentifier testComponent = new ComponentIdentifier("test.component", new Semver("1.0.0"));
        ComponentRecipe mockRecipe = mock(ComponentRecipe.class);
        Optional<ComponentRecipe> recipeResult = Optional.of(mockRecipe);
        // Private ECR image
        List<ComponentArtifact> artifacts = Collections
                .singletonList(ComponentArtifact.builder().artifactUri(new URI("s3://some/artifact")).build());
        when(mockRecipe.getArtifacts()).thenReturn(artifacts);
        when(componentStore.findPackageRecipe(any())).thenReturn(recipeResult);

        List<ComponentIdentifier> dependencyClosure = Arrays.asList(testComponent);
        componentManager.checkPreparePackagesPrerequisites(dependencyClosure);
    }

    @Test
    void GIVEN_deployment_WHEN_dependency_closure_is_missing_download_prereq_component_THEN_fail() throws Exception {
        ComponentIdentifier testComponent = new ComponentIdentifier("test.component", new Semver("1.0.0"));
        ComponentRecipe mockRecipe = mock(ComponentRecipe.class);
        Optional<ComponentRecipe> recipeResult = Optional.of(mockRecipe);
        // Private ECR image
        List<ComponentArtifact> artifacts = Collections
                .singletonList(ComponentArtifact.builder().artifactUri(new URI("s3://some/artifact")).build());
        when(mockRecipe.getArtifacts()).thenReturn(artifacts);
        when(componentStore.findPackageRecipe(any())).thenReturn(recipeResult);
        doThrow(new MissingRequiredComponentsException("Missing required component for download"))
                .when(artifactDownloaderFactory)
                .checkDownloadPrerequisites(any(), any(), any());

        List<ComponentIdentifier> dependencyClosure = Arrays.asList(testComponent);
        assertThrows(MissingRequiredComponentsException.class,
                () -> componentManager.checkPreparePackagesPrerequisites(dependencyClosure));
    }

    private GreengrassService getMockGreengrassService(String serviceName) {
        GreengrassService mockService = mock(GreengrassService.class);
        Topics mockServiceConfig = mock(Topics.class);
        Topic mockVersionTopic = mock(Topic.class);
        when(mockVersionTopic.getOnce()).thenReturn("2.0.0");
        Topic mockPrevVersionTopic = mock(Topic.class);
        when(mockPrevVersionTopic.getOnce()).thenReturn("1.0.0");
        when(mockServiceConfig.find(VERSION_CONFIG_KEY)).thenReturn(mockVersionTopic);
        when(mockServiceConfig.find(PREV_VERSION_CONFIG_KEY)).thenReturn(mockPrevVersionTopic);

        when(mockService.getName()).thenReturn(serviceName);
        when(mockService.getServiceConfig()).thenReturn(mockServiceConfig);
        return mockService;
    }
}
