/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.packagemanager.plugins.GreengrassRepositoryDownloader;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith({MockitoExtension.class, EGExtension.class})
class PackageManagerTest {

    private static final Path RECIPE_RESOURCE_PATH =
            Paths.get(PackageManagerTest.class.getResource("recipes").getPath());

    private static final String MONITORING_SERVICE_PKG_NAME = "MonitoringService";
    private static final String ACTIVE_VERSION_STR = "2.0.0";
    private static final Semver ACTIVE_VERSION = new Semver(ACTIVE_VERSION_STR);

    @TempDir
    Path tempDir;

    private PackageManager packageManager;

    @Mock
    private GreengrassRepositoryDownloader artifactDownloader;

    @Mock
    private GreengrassPackageServiceHelper packageServiceHelper;

    @Mock
    private Kernel kernel;

    @Mock
    private PackageStore packageStore;

    @Mock
    private EvergreenService mockService;

    @BeforeEach
    void beforeEach() {
        packageManager =
                new PackageManager(artifactDownloader, packageServiceHelper, Executors.newSingleThreadExecutor(),
                        packageStore, kernel);
    }


    @Test
    void GIVEN_artifact_list_empty_WHEN_attempt_download_artifact_THEN_do_nothing() throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0.0"), "CoolServiceARN");

        when(packageStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        packageManager.downloadArtifactsIfNecessary(pkgId, Collections.emptyList());

        verify(artifactDownloader, never()).downloadToPath(any(), any(), any());
    }

    @Test
    void GIVEN_artifact_list_WHEN_attempt_download_artifact_THEN_invoke_downloader() throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0.0"), "CoolServiceARN");

        when(packageStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        packageManager.downloadArtifactsIfNecessary(pkgId,
                Arrays.asList(new URI("greengrass:binary1"), new URI("greengrass:binary2")));

        ArgumentCaptor<URI> uriArgumentCaptor = ArgumentCaptor.forClass(URI.class);
        verify(artifactDownloader, times(2)).downloadToPath(eq(pkgId), uriArgumentCaptor.capture(), eq(tempDir));
        List<URI> uriList = uriArgumentCaptor.getAllValues();
        assertThat(uriList.size(), is(2));
        assertThat(uriList.get(0).getSchemeSpecificPart(), is("binary1"));
        assertThat(uriList.get(1).getSchemeSpecificPart(), is("binary2"));
    }


    @Test
    void GIVEN_artifact_provider_not_supported_WHEN_attempt_download_THEN_throw_package_exception() {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0.0"), "CoolServiceARN");
        when(packageStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        Exception exception = assertThrows(PackageLoadingException.class, () -> packageManager
                .downloadArtifactsIfNecessary(pkgId, Collections.singletonList(new URI("docker:image1"))));
        assertThat(exception.getMessage(), is("artifact URI scheme DOCKER is not supported yet"));
    }

    @Test
    void GIVEN_artifact_url_no_scheme_WHEN_attempt_download_THEN_throw_package_exception() {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0" + ".0"), "CoolServiceARN");

        when(packageStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);
        Exception exception = assertThrows(PackageLoadingException.class, () -> packageManager
                .downloadArtifactsIfNecessary(pkgId, Collections.singletonList(new URI("binary1"))));
        assertThat(exception.getMessage(), is("artifact URI scheme null is not supported yet"));
    }

    @Test
    void GIVEN_package_identifier_WHEN_request_to_prepare_package_THEN_task_succeed() throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("SomeService", new Semver("1.0.0"), "PackageARN");
        when(packageStore.resolveArtifactDirectoryPath(pkgId)).thenReturn(tempDir);

        String fileName = "MonitoringService-1.0.0.yaml";
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);

        PackageRecipe pkg = SerializerFactory.getRecipeSerializer()
                .readValue(new String(Files.readAllBytes(sourceRecipe)), PackageRecipe.class);

        when(packageServiceHelper.downloadPackageRecipe(any())).thenReturn(pkg);
        Future<Void> future = packageManager.preparePackages(Collections.singletonList(pkgId));
        future.get(5, TimeUnit.SECONDS);

        assertThat(future.isDone(), is(true));

        verify(packageServiceHelper).downloadPackageRecipe(pkgId);
    }

    @Test
    void GIVEN_package_service_error_out_WHEN_request_to_prepare_package_THEN_task_error_out(ExtensionContext context)
            throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("SomeService", new Semver("1.0.0"), "PackageARN");
        when(packageServiceHelper.downloadPackageRecipe(any())).thenThrow(PackageDownloadException.class);
        ignoreExceptionUltimateCauseOfType(context, PackageDownloadException.class);

        Future<Void> future = packageManager.preparePackages(Collections.singletonList(pkgId));
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_prepare_packages_running_WHEN_prepare_cancelled_THEN_task_stops()
            throws Exception {
        PackageIdentifier pkgId1 = new PackageIdentifier("MonitoringService", new Semver("1.0.0"), "PackageARN");
        PackageIdentifier pkgId2 = new PackageIdentifier("CoolService", new Semver("1.0.0"), "CoolServiceARN");

        String fileName = "MonitoringService-1.0.0.yaml";
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);
        PackageRecipe pkg1 = SerializerFactory.getRecipeSerializer()
                .readValue(new String(Files.readAllBytes(sourceRecipe)), PackageRecipe.class);

        CountDownLatch startedPreparingPkgId1 = new CountDownLatch(1);
        when(packageServiceHelper.downloadPackageRecipe(pkgId1)).thenAnswer(
                invocationOnMock -> {
                    startedPreparingPkgId1.countDown();
                    Thread.sleep(2_000);
                    return pkg1;
                });

        Future<Void> future = packageManager.preparePackages(Arrays.asList(pkgId1, pkgId2));
        assertTrue(startedPreparingPkgId1.await(1, TimeUnit.SECONDS));
        future.cancel(true);

        verify(packageServiceHelper).downloadPackageRecipe(pkgId1);
        verify(packageServiceHelper, times(0)).downloadPackageRecipe(pkgId2);
    }

    @Test
    void GIVEN_package_has_active_version_WHEN_listAvailablePackageMetadata_THEN_return_active_version_first()
            throws Exception {

        // GIVEN
        Topics serviceConfigTopics = Mockito.mock(Topics.class);
        Topic versionTopic = Mockito.mock(Topic.class);

        when(kernel.locate(MONITORING_SERVICE_PKG_NAME)).thenReturn(mockService);
        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(ACTIVE_VERSION_STR);


        Requirement requirement = Requirement.buildNPM(">=1.0.0 <3.0.0");

        // local versions available: 1.0.0, 1.1.0, 2.0.0 (active).
        PackageMetadata packageMetadata_1_0_0 =
                new PackageMetadata(new PackageIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.0.0")),
                        getExpectedDependencies(new Semver("1.0.0")));

        PackageMetadata packageMetadata_1_1_0 =
                new PackageMetadata(new PackageIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.1.0")),
                        getExpectedDependencies(new Semver("1.1.0")));

        PackageMetadata packageMetadata_2_0_0 =
                new PackageMetadata(new PackageIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("2.0.0")),
                        getExpectedDependencies(new Semver("2.0.0")));

        // new ArrayList here because the return list needs to be mutable
        when(packageStore.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement)).thenReturn(
                new ArrayList<>(Arrays.asList(packageMetadata_1_0_0, packageMetadata_1_1_0, packageMetadata_2_0_0)));


        when(packageStore.getPackageMetadata(new PackageIdentifier(MONITORING_SERVICE_PKG_NAME, ACTIVE_VERSION)))
                .thenReturn(packageMetadata_2_0_0);

        // WHEN
        Iterator<PackageMetadata> iterator =
                packageManager.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        // expected return: 2.0.0 (active), 1.0.0, 1.1.0.
        assertThat(iterator.hasNext(), is(true));

        // 2.0.0 (active version)
        PackageMetadata packageMetadata = iterator.next();
        assertThat(packageMetadata, is(packageMetadata_2_0_0));

        // 1.0.0
        packageMetadata = iterator.next();
        assertThat(packageMetadata, is(packageMetadata_1_0_0));

        // 1.1.0
        packageMetadata = iterator.next();
        assertThat(packageMetadata, is(packageMetadata_1_1_0));

        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    void GIVEN_package_has_no_active_version_WHEN_listAvailablePackageMetadata_THEN_return_local_versions()
            throws Exception {

        // GIVEN
        when(kernel.locate(MONITORING_SERVICE_PKG_NAME)).thenThrow(new ServiceLoadException("no service"));

        // local versions available: 1.0.0, 1.1.0.
        Requirement requirement = Requirement.buildNPM(">=1.0.0 <3.0.0");

        PackageMetadata packageMetadata_1_0_0 =
                new PackageMetadata(new PackageIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.0.0")),
                        getExpectedDependencies(new Semver("1.0.0")));

        PackageMetadata packageMetadata_1_1_0 =
                new PackageMetadata(new PackageIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.1.0")),
                        getExpectedDependencies(new Semver("1.1.0")));

        when(packageStore.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement))
                .thenReturn(Arrays.asList(packageMetadata_1_0_0, packageMetadata_1_1_0));

        // WHEN
        Iterator<PackageMetadata> iterator =
                packageManager.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        // expected return: 1.0.0, 1.1.0
        assertThat(iterator.hasNext(), is(true));

        // 1.0.0
        PackageMetadata packageMetadata = iterator.next();
        assertThat(packageMetadata, is(packageMetadata_1_0_0));

        // 1.1.0
        packageMetadata = iterator.next();
        assertThat(packageMetadata, is(packageMetadata_1_1_0));

        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    void GIVEN_active_version_not_satisfied_WHEN_listAvailablePackageMetadata_THEN_return_local_versions()
            throws Exception {

        // GIVEN
        Topics serviceConfigTopics = Mockito.mock(Topics.class);
        Topic versionTopic = Mockito.mock(Topic.class);


        when(kernel.locate(MONITORING_SERVICE_PKG_NAME)).thenReturn(mockService);
        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(ACTIVE_VERSION);

        // local versions available: 1.0.0, 1.1.0.
        Requirement requirement = Requirement.buildNPM(">=1.0.0 <2.0.0");

        PackageMetadata packageMetadata_1_0_0 =
                new PackageMetadata(new PackageIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.0.0")),
                        getExpectedDependencies(new Semver("1.0.0")));

        PackageMetadata packageMetadata_1_1_0 =
                new PackageMetadata(new PackageIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("1.1.0")),
                        getExpectedDependencies(new Semver("1.1.0")));

        when(packageStore.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement))
                .thenReturn(Arrays.asList(packageMetadata_1_0_0, packageMetadata_1_1_0));


        // WHEN
        Iterator<PackageMetadata> iterator =
                packageManager.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        // expected return: 1.0.0, 1.1.0

        // 1.0.0
        PackageMetadata packageMetadata = iterator.next();
        assertThat(packageMetadata, is(packageMetadata_1_0_0));

        // 1.1.0
        packageMetadata = iterator.next();
        assertThat(packageMetadata, is(packageMetadata_1_1_0));

        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    void GIVEN_service_has_version_WHEN_getPackageVersionFromService_THEN_returnIt() {
        Topics serviceConfigTopics = Mockito.mock(Topics.class);
        Topic versionTopic = Mockito.mock(Topic.class);

        when(mockService.getServiceConfig()).thenReturn(serviceConfigTopics);
        when(serviceConfigTopics.findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY)).thenReturn(versionTopic);
        when(versionTopic.getOnce()).thenReturn(ACTIVE_VERSION_STR);

        assertThat(packageManager.getPackageVersionFromService(mockService), is(ACTIVE_VERSION));
    }

    private static Map<String, String> getExpectedDependencies(Semver version) {
        return new HashMap<String, String>() {{
            put("Log", version.toString());
            put("Cool-Database", version.toString());
        }};
    }
}
