/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.amazon.aws.iot.greengrass.component.common.Unarchive;
import com.amazonaws.services.evergreen.model.ComponentContent;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.exceptions.ComponentVersionNegotiationException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.exceptions.SizeLimitException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.componentmanager.plugins.GreengrassRepositoryDownloader;
import com.aws.greengrass.componentmanager.plugins.S3Downloader;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Digest;
import com.aws.greengrass.util.NucleusPaths;
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

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;


@ExtendWith({MockitoExtension.class, GGExtension.class})
class ComponentManagerTest {

    private static Path RECIPE_RESOURCE_PATH;

    static {
        try {
            RECIPE_RESOURCE_PATH = Paths.get(ComponentManagerTest.class.getResource("recipes").toURI());
        } catch (URISyntaxException ignore) {
        }
    }

    private static final String MONITORING_SERVICE_PKG_NAME = "MonitoringService";
    private static final String ACTIVE_VERSION_STR = "2.0.0";
    private static final Semver ACTIVE_VERSION = new Semver(ACTIVE_VERSION_STR);

    private static final String DEPLOYMENT_CONFIGURATION_ID = "deploymentConfigurationId";

    private static final Semver v1_2_0 = new Semver("1.2.0");
    private static final Semver v1_0_0 = new Semver("1.0.0");
    private static final String componentA = "A";
    private static final long TEN_TERA_BYTES = 10_000_000_000_000L;
    private static final long TEN_BYTES = 10L;

    @TempDir
    Path tempDir;

    private ComponentManager componentManager;

    @Mock
    private GreengrassRepositoryDownloader artifactDownloader;
    @Mock
    private S3Downloader s3Downloader;

    @Mock
    private ComponentServiceHelper packageServiceHelper;
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void beforeEach() throws Exception {
        lenient().when(artifactDownloader.downloadRequired(any(),any(), any())).thenReturn(true);
        lenient().when(s3Downloader.downloadRequired(any(),any(), any())).thenReturn(true);
        lenient().when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);
        Topic maxSizeTopic = Topic.of(context, COMPONENT_STORE_MAX_SIZE_BYTES, COMPONENT_STORE_MAX_SIZE_DEFAULT_BYTES);
        lenient().when(deviceConfiguration.getComponentStoreMaxSizeBytes()).thenReturn(maxSizeTopic);
        lenient().when(componentStore.getUsableSpace()).thenReturn(100_000_000L);
        componentManager = new ComponentManager(s3Downloader, artifactDownloader, packageServiceHelper,
                executor, componentStore, kernel, mockUnarchiver, deviceConfiguration, nucleusPaths);
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

        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        componentManager.prepareArtifacts(pkgId, Collections.emptyList());

        verify(artifactDownloader, never()).downloadToPath(any(), any(), any());
    }

    @Test
    void GIVEN_artifact_from_gg_repo_WHEN_attempt_download_artifact_THEN_invoke_gg_downloader() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));

        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        componentManager.prepareArtifacts(pkgId,
                Arrays.asList(new ComponentArtifact(new URI("greengrass:binary1"), null, null, null),
                        new ComponentArtifact(new URI("greengrass:binary2"), null, null, null)));

        ArgumentCaptor<ComponentArtifact> artifactArgumentCaptor = ArgumentCaptor.forClass(ComponentArtifact.class);
        verify(artifactDownloader, times(2)).downloadToPath(eq(pkgId), artifactArgumentCaptor.capture(), eq(tempDir));
        List<ComponentArtifact> artifactsList = artifactArgumentCaptor.getAllValues();
        assertThat(artifactsList.size(), is(2));
        assertThat(artifactsList.get(0).getArtifactUri().getSchemeSpecificPart(), is("binary1"));
        assertThat(artifactsList.get(1).getArtifactUri().getSchemeSpecificPart(), is("binary2"));
    }

    @Test
    void GIVEN_artifact_from_gg_repo_WHEN_download_artifact_with_unarchive_THEN_calls_unarchiver() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));

        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);
        when(artifactDownloader.downloadToPath(any(), any(), any())).thenReturn(new File("binary1"));
        when(artifactDownloader.getArtifactFile(any(), any(), any())).thenReturn(new File("binary1"));

        componentManager.prepareArtifacts(pkgId,
                Arrays.asList(new ComponentArtifact(new URI("greengrass:binary1"), null, null, Unarchive.ZIP),
                        new ComponentArtifact(new URI("greengrass:binary2"), null, null, Unarchive.NONE)));

        ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
        verify(mockUnarchiver).unarchive(any(), fileCaptor.capture(), any());
        assertEquals("binary1", fileCaptor.getValue().getName());
    }

    @Test
    void GIVEN_artifact_from_s3_WHEN_attempt_download_THEN_invoke_s3_downloader() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("SomeServiceWithArtifactsInS3", new Semver("1.0.0"));

        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        componentManager.prepareArtifacts(pkgId,
                Collections.singletonList(new ComponentArtifact(new URI("s3://bucket/path/to/key"), null, null, null)));

        ArgumentCaptor<ComponentArtifact> artifactArgumentCaptor = ArgumentCaptor.forClass(ComponentArtifact.class);
        verify(s3Downloader, times(1)).downloadToPath(eq(pkgId), artifactArgumentCaptor.capture(), eq(tempDir));
        List<ComponentArtifact> artifactsList = artifactArgumentCaptor.getAllValues();
        assertThat(artifactsList.size(), is(1));
        assertThat(artifactsList.get(0).getArtifactUri().getSchemeSpecificPart(), is("//bucket/path/to/key"));
    }

    @Test
    void GIVEN_artifact_provider_not_supported_WHEN_attempt_download_THEN_throw_package_exception()
            throws PackageLoadingException {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));
        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        Exception exception = assertThrows(PackageLoadingException.class, () -> componentManager.prepareArtifacts(pkgId,
                Collections.singletonList(new ComponentArtifact(new URI("docker:image1"), null, null, null))));
        assertThat(exception.getMessage(), is("artifact URI scheme DOCKER is not supported yet"));
    }

    @Test
    void GIVEN_artifact_url_no_scheme_WHEN_attempt_download_THEN_throw_package_exception()
            throws PackageLoadingException {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0" + ".0"));

        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);
        Exception exception = assertThrows(PackageLoadingException.class, () -> componentManager.prepareArtifacts(pkgId,
                Collections.singletonList(new ComponentArtifact(new URI("binary1"), null, null, null))));
        assertThat(exception.getMessage(), is("artifact URI scheme null is not supported yet"));
    }

    @Test
    void GIVEN_package_identifier_WHEN_request_to_prepare_package_THEN_task_succeed() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("MonitoringService", new Semver("1.0.0"));
        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        String fileName = "MonitoringService-1.0.0.yaml";
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);

        String sourceRecipeString = new String(Files.readAllBytes(sourceRecipe));
        ComponentRecipe componentRecipe = RecipeLoader.loadFromFile(sourceRecipeString).get();


        when(packageServiceHelper.downloadPackageRecipeAsString(any())).thenReturn(sourceRecipeString);
        when(componentStore.getPackageRecipe(pkgId)).thenReturn(componentRecipe);
        Future<Void> future = componentManager.preparePackages(Collections.singletonList(pkgId));
        future.get(5, TimeUnit.SECONDS);

        assertThat(future.isDone(), is(true));

        verify(packageServiceHelper).downloadPackageRecipeAsString(pkgId);
        verify(componentStore).findPackageRecipe(pkgId);
        verify(componentStore).savePackageRecipe(pkgId, sourceRecipeString);
        verify(componentStore).getPackageRecipe(pkgId);
        verifyNoMoreInteractions(componentStore);

    }

    @Test
    void GIVEN_package_service_error_out_WHEN_request_to_prepare_package_THEN_task_error_out(ExtensionContext context)
            throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("SomeService", new Semver("1.0.0"));
        when(packageServiceHelper.downloadPackageRecipeAsString(any())).thenThrow(PackageDownloadException.class);
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
        ComponentRecipe pkg1 = RecipeLoader.loadFromFile(new String(Files.readAllBytes(sourceRecipe))).get();

        CountDownLatch startedPreparingPkgId1 = new CountDownLatch(1);
        when(packageServiceHelper.downloadPackageRecipeAsString(pkgId1)).thenAnswer(invocationOnMock -> {
            startedPreparingPkgId1.countDown();
            Thread.sleep(2_000);
            return pkg1;
        });

        Future<Void> future = componentManager.preparePackages(Arrays.asList(pkgId1, pkgId2));
        assertTrue(startedPreparingPkgId1.await(1, TimeUnit.SECONDS));
        future.cancel(true);

        verify(packageServiceHelper).downloadPackageRecipeAsString(pkgId1);
        verify(packageServiceHelper, times(0)).downloadPackageRecipeAsString(pkgId2);
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
    void GIVEN_component_is_local_override_WHEN_resolve_version_THEN_use_local_version() throws Exception {
        ComponentIdentifier componentA_1_2_0 = new ComponentIdentifier(componentA, v1_2_0);
        ComponentMetadata componentA_1_2_0_md = new ComponentMetadata(componentA_1_2_0, Collections.emptyMap());
        when(componentStore.findBestMatchAvailableComponent(eq(componentA), any()))
                .thenReturn(Optional.of(componentA_1_2_0));
        when(componentStore.getPackageMetadata(any())).thenReturn(componentA_1_2_0_md);

        ComponentMetadata componentMetadata = componentManager
                .resolveComponentVersion(componentA, Collections.singletonMap("LOCAL", Requirement.buildNPM("^1.0")),
                        DEPLOYMENT_CONFIGURATION_ID);

        assertThat(componentMetadata, is(componentA_1_2_0_md));
        verify(componentStore).findBestMatchAvailableComponent(componentA, Requirement.buildNPM("^1.0"));
        verify(componentStore).getPackageMetadata(componentA_1_2_0);
        verify(packageServiceHelper, never()).resolveComponentVersion(anyString(), any(), any(), anyString());
    }

    @Test
    void GIVEN_component_is_local_active_WHEN_cloud_resolve_to_different_recipe_THEN_update_recipe() throws Exception {
        ComponentIdentifier componentA_1_0_0 = new ComponentIdentifier(componentA, v1_0_0);
        ComponentMetadata componentA_1_0_0_md = new ComponentMetadata(componentA_1_0_0, Collections.emptyMap());
        ObjectMapper mapper = new ObjectMapper();
        Topics serviceConfigTopics = mock(Topics.class);
        Topic versionTopic = mock(Topic.class);
        Topics runtimeTopics = mock(Topics.class);
        Topic digestTopic = mock(Topic.class);

        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe oldRecipe =
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .componentName("SampleComponent")
                        .componentVersion(new Semver("1.0.0"))
                        .componentType(ComponentType.PLUGIN)
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                        .build();

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
        when(runtimeTopics.lookup(any(), any())).thenReturn(digestTopic);
        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(v1_0_0.getValue());

        ComponentContent componentContent = new ComponentContent().withName(componentA).withVersion(v1_0_0.getValue())
                .withRecipe(ByteBuffer.wrap(mapper.writeValueAsBytes(newRecipe)));
        when(packageServiceHelper.resolveComponentVersion(anyString(), any(), any(), anyString()))
                .thenReturn(componentContent);
        when(componentStore.findComponentRecipeContent(any()))
                .thenReturn(Optional.of(mapper.writeValueAsString(oldRecipe)));
        when(componentStore.getPackageMetadata(any())).thenReturn(componentA_1_0_0_md);

        ComponentMetadata componentMetadata = componentManager
                .resolveComponentVersion(componentA, Collections.singletonMap("X", Requirement.buildNPM("^1.0")),
                        DEPLOYMENT_CONFIGURATION_ID);

        assertThat(componentMetadata, is(componentA_1_0_0_md));
        verify(packageServiceHelper).resolveComponentVersion(componentA, v1_0_0,
                Collections.singletonMap("X", Requirement.buildNPM("^1.0")), DEPLOYMENT_CONFIGURATION_ID);
        verify(componentStore).findComponentRecipeContent(componentA_1_0_0);
        verify(componentStore).savePackageRecipe(componentA_1_0_0, mapper.writeValueAsString(newRecipe));
        verify(componentStore).getPackageMetadata(componentA_1_0_0);
        String recipeString = new String(componentContent.getRecipe().array(), StandardCharsets.UTF_8);
        verify(digestTopic).withValue(Digest.calculate(recipeString));
    }

    @Test
    void GIVEN_component_is_builtin_service_WHEN_cloud_service_exception_THEN_resolve_to_local_version()
            throws Exception {
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

        when(packageServiceHelper.resolveComponentVersion(anyString(), any(), any(), anyString()))
                .thenThrow(ComponentVersionNegotiationException.class);
        when(componentStore.getPackageMetadata(any())).thenThrow(PackagingException.class);

        ComponentMetadata componentMetadata = componentManager
                .resolveComponentVersion(componentA, Collections.singletonMap("X", Requirement.buildNPM("^1.0")),
                        DEPLOYMENT_CONFIGURATION_ID);

        assertThat(componentMetadata, is(componentA_1_0_0_md));
        verify(componentStore, never()).findComponentRecipeContent(any());
        verify(componentStore, never()).savePackageRecipe(any(), anyString());
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
        ComponentRecipe componentRecipe = RecipeLoader.loadFromFile(sourceRecipeString).get();
        when(packageServiceHelper.downloadPackageRecipeAsString(any())).thenReturn(sourceRecipeString);
        when(componentStore.getPackageRecipe(pkgId)).thenReturn(componentRecipe);

        // mock very limited space left
        when(componentStore.getUsableSpace()).thenReturn(TEN_BYTES);

        ignoreExceptionUltimateCauseOfType(context, SizeLimitException.class);
        Future<Void> future = componentManager.preparePackages(Collections.singletonList(pkgId));
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        verify(artifactDownloader, never()).downloadToPath(any(), any(), any());
    }

    @Test
    void GIVEN_component_WHEN_component_store_full_and_prepare_components_THEN_throws_exception(ExtensionContext context)
            throws Exception {
        // mock get recipe
        ComponentIdentifier pkgId = new ComponentIdentifier("SimpleApp", new Semver("1.0.0"));
        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);
        String fileName = "SimpleApp-1.0.0.yaml";
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);
        String sourceRecipeString = new String(Files.readAllBytes(sourceRecipe));
        ComponentRecipe componentRecipe = RecipeLoader.loadFromFile(sourceRecipeString).get();
        when(packageServiceHelper.downloadPackageRecipeAsString(any())).thenReturn(sourceRecipeString);
        when(componentStore.getPackageRecipe(pkgId)).thenReturn(componentRecipe);

        // mock very large component store size
        when(componentStore.getContentSize()).thenReturn(TEN_TERA_BYTES);
        when(artifactDownloader.getDownloadSize(any(), any(), any())).thenReturn(TEN_BYTES);

        ignoreExceptionUltimateCauseOfType(context, SizeLimitException.class);
        Future<Void> future = componentManager.preparePackages(Collections.singletonList(pkgId));
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        verify(artifactDownloader, never()).downloadToPath(any(), any(), any());
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
        when(runtimeTopics.find(any(), identifierCaptor.capture()))
                .thenReturn(digestTopic);

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
                new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("3.0.0")));
        verify(componentStore, times(1)).deleteComponent(
                new ComponentIdentifier(anotherCompName, new Semver("1.0.0")));
        verify(componentStore, times(1)).deleteComponent(
                new ComponentIdentifier(anotherCompName, new Semver("2.0.0")));

        // verify digest was cleaned up
        verify(digestTopic, times(3)).remove();
        assertThat(identifierCaptor.getAllValues(), containsInAnyOrder(MONITORING_SERVICE_PKG_NAME + "-v3.0.0",
                anotherCompName + "-v1.0.0", anotherCompName + "-v2.0.0"));
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
