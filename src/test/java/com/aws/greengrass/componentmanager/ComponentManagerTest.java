/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.Unarchive;
import com.amazonaws.services.evergreen.model.ComponentContent;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.exceptions.ComponentVersionNegotiationException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.componentmanager.plugins.GreengrassRepositoryDownloader;
import com.aws.greengrass.componentmanager.plugins.S3Downloader;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.codec.Charsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.componentmanager.models.ComponentIdentifier.PUBLIC_SCOPE;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private static final String SCOPE = "private";

    private static final String DEPLOYMENT_CONFIGURATION_ID = "deploymentConfigurationId";

    private static final Semver v1_2_0 = new Semver("1.2.0");
    private static final Semver v1_0_0 = new Semver("1.0.0");
    private static final String componentA = "A";

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
    private ComponentStore componentStore;
    @Mock
    private GreengrassService mockService;
    @Mock
    private Unarchiver mockUnarchiver;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @BeforeEach
    void beforeEach() {
        componentManager =
                new ComponentManager(s3Downloader, artifactDownloader, packageServiceHelper, executor, componentStore,
                        kernel, mockUnarchiver);
    }

    @AfterEach
    void after() {
        executor.shutdownNow();
    }

    @Test
    void GIVEN_artifact_list_empty_WHEN_attempt_download_artifact_THEN_do_nothing() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"), SCOPE);

        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        componentManager.prepareArtifacts(pkgId, Collections.emptyList());

        verify(artifactDownloader, never()).downloadToPath(any(), any(), any());
    }

    @Test
    void GIVEN_artifact_from_gg_repo_WHEN_attempt_download_artifact_THEN_invoke_gg_downloader() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"), SCOPE);

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
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"), SCOPE);

        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);
        when(componentStore.resolveAndSetupArtifactsDecompressedDirectory(pkgId)).thenReturn(tempDir);
        when(artifactDownloader.downloadToPath(any(), any(), any())).thenReturn(new File("binary1"));

        componentManager.prepareArtifacts(pkgId,
                Arrays.asList(new ComponentArtifact(new URI("greengrass:binary1"), null, null, Unarchive.ZIP),
                        new ComponentArtifact(new URI("greengrass:binary2"), null, null, Unarchive.NONE)));

        ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
        verify(mockUnarchiver).unarchive(any(), fileCaptor.capture(), any());
        assertEquals("binary1", fileCaptor.getValue().getName());
    }

    @Test
    void GIVEN_artifact_from_s3_WHEN_attempt_download_THEN_invoke_s3_downloader() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("SomeServiceWithArtifactsInS3", new Semver("1.0.0"), SCOPE);

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
    void GIVEN_artifact_provider_not_supported_WHEN_attempt_download_THEN_throw_package_exception() {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"), SCOPE);
        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        Exception exception = assertThrows(PackageLoadingException.class, () -> componentManager.prepareArtifacts(pkgId,
                Collections.singletonList(new ComponentArtifact(new URI("docker:image1"), null, null, null))));
        assertThat(exception.getMessage(), is("artifact URI scheme DOCKER is not supported yet"));
    }

    @Test
    void GIVEN_artifact_url_no_scheme_WHEN_attempt_download_THEN_throw_package_exception() {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0" + ".0"), SCOPE);

        when(componentStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);
        Exception exception = assertThrows(PackageLoadingException.class, () -> componentManager.prepareArtifacts(pkgId,
                Collections.singletonList(new ComponentArtifact(new URI("binary1"), null, null, null))));
        assertThat(exception.getMessage(), is("artifact URI scheme null is not supported yet"));
    }

    @Test
    void GIVEN_package_identifier_WHEN_request_to_prepare_package_THEN_task_succeed() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("MonitoringService", new Semver("1.0.0"), SCOPE);
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
        ComponentIdentifier pkgId = new ComponentIdentifier("SomeService", new Semver("1.0.0"), SCOPE);
        when(packageServiceHelper.downloadPackageRecipeAsString(any())).thenThrow(PackageDownloadException.class);
        ignoreExceptionUltimateCauseOfType(context, PackageDownloadException.class);

        Future<Void> future = componentManager.preparePackages(Collections.singletonList(pkgId));
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_prepare_packages_running_WHEN_prepare_cancelled_THEN_task_stops() throws Exception {
        ComponentIdentifier pkgId1 = new ComponentIdentifier("MonitoringService", new Semver("1.0.0"), SCOPE);
        ComponentIdentifier pkgId2 = new ComponentIdentifier("CoolService", new Semver("1.0.0"), SCOPE);

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
    void GIVEN_package_has_active_version_WHEN_listAvailablePackageMetadata_THEN_return_active_version_first()
            throws Exception {

        // GIVEN
        Topics serviceConfigTopics = mock(Topics.class);
        Topic versionTopic = mock(Topic.class);

        when(kernel.findServiceTopic(MONITORING_SERVICE_PKG_NAME)).thenReturn(mock(Topics.class));
        when(kernel.locate(MONITORING_SERVICE_PKG_NAME)).thenReturn(mockService);
        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(ACTIVE_VERSION_STR);


        Requirement requirement = Requirement.buildNPM(">=1.0.0 <3.0.0");

        // local versions available: 1.0.0, 1.1.0, 2.0.0 (active).
        ComponentMetadata componentMetadata_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.0.0")),
                        getExpectedDependencies(new Semver("1.0.0")));

        ComponentMetadata componentMetadata_1_1_0 =
                new ComponentMetadata(new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.1.0")),
                        getExpectedDependencies(new Semver("1.1.0")));

        ComponentMetadata componentMetadata_2_0_0 =
                new ComponentMetadata(new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("2.0.0")),
                        getExpectedDependencies(new Semver("2.0.0")));

        // new ArrayList here because the return list needs to be mutable
        when(componentStore.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement)).thenReturn(
                new ArrayList<>(
                        Arrays.asList(componentMetadata_1_0_0, componentMetadata_1_1_0, componentMetadata_2_0_0)));


        when(componentStore.getPackageMetadata(new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, ACTIVE_VERSION)))
                .thenReturn(componentMetadata_2_0_0);

        // WHEN
        Iterator<ComponentMetadata> iterator =
                componentManager.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        // expected return: 2.0.0 (active), 1.0.0, 1.1.0.
        assertThat(iterator.hasNext(), is(true));

        // 2.0.0 (active version)
        ComponentMetadata componentMetadata = iterator.next();
        assertThat(componentMetadata, is(componentMetadata_2_0_0));

        // 1.0.0
        componentMetadata = iterator.next();
        assertThat(componentMetadata, is(componentMetadata_1_0_0));

        // 1.1.0
        componentMetadata = iterator.next();
        assertThat(componentMetadata, is(componentMetadata_1_1_0));

        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    void GIVEN_package_has_no_active_version_WHEN_listAvailablePackageMetadata_THEN_return_local_versions()
            throws Exception {

        // GIVEN
        when(kernel.findServiceTopic(MONITORING_SERVICE_PKG_NAME)).thenReturn(mock(Topics.class));
        when(kernel.locate(MONITORING_SERVICE_PKG_NAME)).thenThrow(new ServiceLoadException("no service"));

        // local versions available: 1.0.0, 1.1.0.
        Requirement requirement = Requirement.buildNPM(">=1.0.0 <3.0.0");

        ComponentMetadata componentMetadata_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.0.0")),
                        getExpectedDependencies(new Semver("1.0.0")));

        ComponentMetadata componentMetadata_1_1_0 =
                new ComponentMetadata(new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.1.0")),
                        getExpectedDependencies(new Semver("1.1.0")));

        when(componentStore.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement))
                .thenReturn(Arrays.asList(componentMetadata_1_0_0, componentMetadata_1_1_0));

        // WHEN
        Iterator<ComponentMetadata> iterator =
                componentManager.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        // expected return: 1.0.0, 1.1.0
        assertThat(iterator.hasNext(), is(true));

        // 1.0.0
        ComponentMetadata componentMetadata = iterator.next();
        assertThat(componentMetadata, is(componentMetadata_1_0_0));

        // 1.1.0
        componentMetadata = iterator.next();
        assertThat(componentMetadata, is(componentMetadata_1_1_0));

        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    void GIVEN_active_version_not_satisfied_WHEN_listAvailablePackageMetadata_THEN_return_local_versions()
            throws Exception {

        // GIVEN
        Topics serviceConfigTopics = mock(Topics.class);
        Topic versionTopic = mock(Topic.class);


        when(kernel.findServiceTopic(MONITORING_SERVICE_PKG_NAME)).thenReturn(mock(Topics.class));
        when(kernel.locate(MONITORING_SERVICE_PKG_NAME)).thenReturn(mockService);
        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(ACTIVE_VERSION);

        // local versions available: 1.0.0, 1.1.0.
        Requirement requirement = Requirement.buildNPM(">=1.0.0 <2.0.0");

        ComponentMetadata componentMetadata_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.0.0")),
                        getExpectedDependencies(new Semver("1.0.0")));

        ComponentMetadata componentMetadata_1_1_0 =
                new ComponentMetadata(new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.1.0")),
                        getExpectedDependencies(new Semver("1.1.0")));

        when(componentStore.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement))
                .thenReturn(Arrays.asList(componentMetadata_1_0_0, componentMetadata_1_1_0));


        // WHEN
        Iterator<ComponentMetadata> iterator =
                componentManager.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        // expected return: 1.0.0, 1.1.0

        // 1.0.0
        ComponentMetadata componentMetadata = iterator.next();
        assertThat(componentMetadata, is(componentMetadata_1_0_0));

        // 1.1.0
        componentMetadata = iterator.next();
        assertThat(componentMetadata, is(componentMetadata_1_1_0));

        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    void GIVEN_service_has_version_WHEN_getPackageVersionFromService_THEN_returnIt() {
        Topics serviceConfigTopics = mock(Topics.class);
        Topic versionTopic = mock(Topic.class);

        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY)).thenReturn(versionTopic);
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
        verify(packageServiceHelper, never()).resolveComponentVersion(anyString(), anyString(), any(), any());
    }

    @Test
    void GIVEN_component_is_local_active_WHEN_cloud_resolve_to_different_recipe_THEN_update_recipe() throws Exception {
        ComponentIdentifier componentA_1_0_0 = new ComponentIdentifier(componentA, v1_0_0);
        ComponentMetadata componentA_1_0_0_md = new ComponentMetadata(componentA_1_0_0, Collections.emptyMap());

        Topics serviceConfigTopics = mock(Topics.class);
        Topic versionTopic = mock(Topic.class);

        when(kernel.findServiceTopic(componentA)).thenReturn(mock(Topics.class));
        when(kernel.locate(componentA)).thenReturn(mockService);
        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(v1_0_0.getValue());

        ComponentContent componentContent = new ComponentContent().withName(componentA).withVersion(v1_0_0.getValue())
                .withRecipe(ByteBuffer.wrap("new recipe".getBytes(Charsets.UTF_8)));
        when(packageServiceHelper.resolveComponentVersion(anyString(), anyString(), any(), any()))
                .thenReturn(componentContent);
        when(componentStore.findComponentRecipeContent(any())).thenReturn(Optional.of("old recipe"));
        when(componentStore.getPackageMetadata(any())).thenReturn(componentA_1_0_0_md);

        ComponentMetadata componentMetadata = componentManager
                .resolveComponentVersion(componentA, Collections.singletonMap("X", Requirement.buildNPM("^1.0")),
                        DEPLOYMENT_CONFIGURATION_ID);

        assertThat(componentMetadata, is(componentA_1_0_0_md));
        verify(packageServiceHelper).resolveComponentVersion(DEPLOYMENT_CONFIGURATION_ID, componentA, v1_0_0,
                Collections.singletonMap("X", Requirement.buildNPM("^1.0")));
        verify(componentStore).findComponentRecipeContent(componentA_1_0_0);
        verify(componentStore).savePackageRecipe(componentA_1_0_0, "new recipe");
        verify(componentStore).getPackageMetadata(componentA_1_0_0);
    }

    @Test
    void GIVEN_component_is_builtin_service_WHEN_cloud_service_exception_THEN_resolve_to_local_version()
            throws Exception {
        ComponentIdentifier componentA_1_0_0 = new ComponentIdentifier(componentA, v1_0_0, PUBLIC_SCOPE);
        ComponentMetadata componentA_1_0_0_md = new ComponentMetadata(componentA_1_0_0, Collections.emptyMap());

        Topics serviceConfigTopics = mock(Topics.class);
        Topic versionTopic = mock(Topic.class);

        when(kernel.findServiceTopic(componentA)).thenReturn(mock(Topics.class));
        when(kernel.locate(componentA)).thenReturn(mockService);
        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(v1_0_0.getValue());
        when(mockService.isBuiltin()).thenReturn(true);

        when(packageServiceHelper.resolveComponentVersion(anyString(), anyString(), any(), any()))
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

    private static Map<String, String> getExpectedDependencies(Semver version) {
        return new HashMap<String, String>() {{
            put("Log", version.toString());
            put("Cool-Database", version.toString());
        }};
    }
}
