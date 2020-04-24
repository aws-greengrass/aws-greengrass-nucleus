package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.plugins.GreengrassRepositoryDownloader;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
class PackageManagerTest {

    private Path testCache;

    private PackageManager packageManager;

    @Mock
    private GreengrassRepositoryDownloader artifactDownloader;

    @Mock
    private GreengrassPackageServiceHelper packageServiceHelper;

    @Mock
    private Kernel kernel;

    private ExecutorService executor;

    @BeforeEach
    void beforeEach() {
        executor = Executors.newSingleThreadExecutor();
        testCache = TestHelper.getPathForLocalTestCache();
        packageManager = new PackageManager(testCache, packageServiceHelper, artifactDownloader,
                executor, kernel);
    }

    @AfterEach
    void cleanTestCache() throws Exception {
        TestHelper.cleanDirectory(testCache);
        executor.shutdownNow();
    }

    @Test
    void GIVEN_path_to_valid_package_recipe_WHEN_attempt_find_package_THEN_package_model_is_returned()
            throws Exception {
        Path recipePath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0")
                .resolve("recipe.yaml");
        Optional<PackageRecipe> packageRecipe = packageManager.findPackageRecipe(recipePath);
        assertThat(packageRecipe.isPresent(), is(true));
    }

    @Test
    void GIVEN_path_to_invalid_package_recipe_WHEN_attempt_find_package_THEN_get_loading_exception() throws Exception {
        Path recipePath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0")
                .resolve("bad_recipe.yaml");

        Exception exception =
                assertThrows(PackageLoadingException.class, () -> packageManager.findPackageRecipe(recipePath));
        assertThat(exception.getMessage().startsWith("Failed to parse package recipe"), is(true));
    }

    @Test
    void GIVEN_invalid_path_to_package_recipe_WHEN_attempt_find_package_THEN_null_is_returned() throws Exception {
        Path recipePath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0")
                .resolve("not_exist_recipe.yaml");

        Optional<PackageRecipe> packageRecipe = packageManager.findPackageRecipe(recipePath);
        assertThat(packageRecipe.isPresent(), is(false));
    }

    @Test
    void GIVEN_package_in_memory_WHEN_attempt_save_package_THEN_successfully_save_to_file() throws Exception {
        Path recipePath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0")
                .resolve("recipe.yaml");
        PackageRecipe packageRecipe = packageManager.findPackageRecipe(recipePath).get();

        Path saveToFile =
                testCache.resolve(String.format("%s-%s.yaml", TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0"));
        packageManager.savePackageRecipeToFile(packageRecipe, saveToFile);

        PackageRecipe savedPackageRecipe = packageManager.findPackageRecipe(saveToFile).get();
        assertThat(savedPackageRecipe, is(packageRecipe));
    }

    @Test
    void GIVEN_artifact_list_empty_WHEN_attempt_download_artifact_THEN_do_nothing() throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0.0"), "CoolServiceARN");

        packageManager.downloadArtifactsIfNecessary(pkgId, Collections.emptyList());

        verify(artifactDownloader, never()).downloadToPath(any(), any(), any());
    }

    @Test
    void GIVEN_artifact_list_WHEN_attempt_download_artifact_THEN_invoke_downloader() throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0.0"), "CoolServiceARN");

        packageManager.downloadArtifactsIfNecessary(pkgId,
                Arrays.asList(new URI("greengrass:binary1"), new URI("greengrass:binary2")));

        ArgumentCaptor<URI> uriArgumentCaptor = ArgumentCaptor.forClass(URI.class);
        verify(artifactDownloader, times(2)).downloadToPath(eq(pkgId), uriArgumentCaptor.capture(),
                eq(testCache.resolve("artifact").resolve("CoolService").resolve("1.0.0")));
        List<URI> uriList = uriArgumentCaptor.getAllValues();
        assertThat(uriList.size(), is(2));
        assertThat(uriList.get(0).getSchemeSpecificPart(), is("binary1"));
        assertThat(uriList.get(1).getSchemeSpecificPart(), is("binary2"));
    }


    @Test
    void GIVEN_artifact_provider_not_supported_WHEN_attempt_download_THEN_throw_package_exception() {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0.0"), "CoolServiceARN");

        Exception exception = assertThrows(PackageLoadingException.class, () -> packageManager
                .downloadArtifactsIfNecessary(pkgId, Collections.singletonList(new URI("docker:image1"))));
        assertThat(exception.getMessage(), is("artifact URI scheme DOCKER is not supported yet"));
    }

    @Test
    void GIVEN_artifact_url_no_scheme_WHEN_attempt_download_THEN_throw_package_exception() {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0" + ".0"), "CoolServiceARN");

        Exception exception = assertThrows(PackageLoadingException.class,
                () -> packageManager.downloadArtifactsIfNecessary(pkgId, Collections.singletonList(new URI("binary1"))));
        assertThat(exception.getMessage(), is("artifact URI scheme null is not supported yet"));
    }

    @Test
    void GIVEN_package_identifier_WHEN_request_to_prepare_package_THEN_task_succeed() throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("SomeService", new Semver("1.0.0"), "PackageARN");
        Path recipePath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0")
                .resolve("recipe.yaml");
        PackageRecipe packageRecipe = packageManager.findPackageRecipe(recipePath).get();
        when(packageServiceHelper.downloadPackageRecipe(any())).thenReturn(packageRecipe);
        Future<Void> future = packageManager.preparePackages(Collections.singletonList(pkgId));
        future.get(5, TimeUnit.SECONDS);

        assertThat(future.isDone(), is(true));

        verify(packageServiceHelper).downloadPackageRecipe(pkgId);
    }

    @Test
    void GIVEN_package_service_error_out_WHEN_request_to_prepare_package_THEN_task_error_out(ExtensionContext context) throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("SomeService", new Semver("1.0.0"), "PackageARN");
        when(packageServiceHelper.downloadPackageRecipe(any())).thenThrow(PackageDownloadException.class);
        ignoreExceptionUltimateCauseOfType(context, PackageDownloadException.class);

        Future<Void> future = packageManager.preparePackages(Collections.singletonList(pkgId));
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    }
}

// TODO migrate the tests above over and remove "test_packages" and "mock_artifact_source".
@Nested
@ExtendWith({MockitoExtension.class, EGExtension.class})
class NewPackageStoreTest {
    private static final String MONITORING_SERVICE_PKG_NAME = "MonitoringService";
    private static final Semver MONITORING_SERVICE_PKG_VERSION = new Semver("1.1.0", Semver.SemverType.NPM);
    private static final PackageIdentifier MONITORING_SERVICE_PKG_ID =
            new PackageIdentifier(MONITORING_SERVICE_PKG_NAME, MONITORING_SERVICE_PKG_VERSION);

    private static final Path TEST_ROOT_PATH =
            Paths.get(PackageManagerTest.class.getResource("test_store_root").getPath());

    private static final String ACTIVE_VERSION_STR = "2.0.0";
    private static final Semver ACTIVE_VERSION = new Semver(ACTIVE_VERSION_STR);

    @Mock
    private GreengrassRepositoryDownloader artifactDownloader;

    @Mock
    private GreengrassPackageServiceHelper packageServiceHelper;

    @Mock
    private Kernel kernel;

    @Mock
    private EvergreenService mockService;

    private PackageManager packageManager;

    @BeforeEach
    void beforeEach() {
        packageManager = new PackageManager(TEST_ROOT_PATH, packageServiceHelper, artifactDownloader,
                Executors.newSingleThreadExecutor(), kernel);
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

        // WHEN
        Requirement requirement = Requirement.buildNPM(">=1.0.0 <3.0.0");
        Iterator<PackageMetadata> iterator =
                packageManager.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN

        // versions available: 1.0.0, 1.1.0, 2.0.0 (active), 3.0.0.
        // expected return: 2.0.0 (active), 1.0.0, 1.1.0.

        assertThat(iterator.hasNext(), is(true));

        // 2.0.0 (active version)
        PackageMetadata packageMetadata = iterator.next();
        assertThat(packageMetadata.getPackageIdentifier().getVersion(), is(ACTIVE_VERSION));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(ACTIVE_VERSION)));

        // 1.0.0
        packageMetadata = iterator.next();
        assertThat(packageMetadata.getPackageIdentifier().getVersion(), is(new Semver("1.0.0")));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(new Semver("1.0.0"))));

        // 1.1.0
        packageMetadata = iterator.next();
        assertThat(packageMetadata.getPackageIdentifier().getVersion(), is(new Semver("1.1.0")));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(new Semver("1.1.0"))));

        assertThat(iterator.hasNext(), is(false));
    }

    @Test
    void GIVEN_package_has_no_active_version_WHEN_listAvailablePackageMetadata_THEN_return_local_versions()
            throws Exception {

        // GIVEN
        when(kernel.locate(MONITORING_SERVICE_PKG_NAME)).thenThrow(new ServiceLoadException("no service"));

        // WHEN
        Requirement requirement = Requirement.buildNPM(">=1.0.0 <3.0.0");
        Iterator<PackageMetadata> iterator =
                packageManager.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN

        // versions available: 1.0.0, 1.1.0, 2.0.0, 3.0.0.
        // expected return: 1.0.0, 1.1.0, 2.0.0

        assertThat(iterator.hasNext(), is(true));

        // 1.0.0
        PackageMetadata packageMetadata = iterator.next();
        assertThat(packageMetadata.getPackageIdentifier().getVersion(), is(new Semver("1.0.0")));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(new Semver("1.0.0"))));

        // 1.1.0
        packageMetadata = iterator.next();
        assertThat(packageMetadata.getPackageIdentifier().getVersion(), is(new Semver("1.1.0")));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(new Semver("1.1.0"))));

        // 2.0.0
        packageMetadata = iterator.next();
        assertThat(packageMetadata.getPackageIdentifier().getVersion(), is(new Semver("2.0.0")));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(new Semver("2.0.0"))));

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

        // WHEN
        Requirement requirement = Requirement.buildNPM(">=1.0.0 <2.0.0");
        Iterator<PackageMetadata> iterator =
                packageManager.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN

        // versions available: 1.0.0, 1.1.0, 2.0.0(active), 3.0.0.
        // expected return: 1.0.0, 1.1.0

        assertThat(iterator.hasNext(), is(true));

        // 1.0.0
        PackageMetadata packageMetadata = iterator.next();
        assertThat(packageMetadata.getPackageIdentifier().getVersion(), is(new Semver("1.0.0")));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(new Semver("1.0.0"))));

        // 1.1.0
        packageMetadata = iterator.next();
        assertThat(packageMetadata.getPackageIdentifier().getVersion(), is(new Semver("1.1.0")));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(new Semver("1.1.0"))));

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

    @Test
    void GIVEN_recipe_exists_WHEN_getPackageMetadata_THEN_returnIt() throws Exception {
        PackageMetadata packageMetadata = packageManager.getPackageMetadata(MONITORING_SERVICE_PKG_ID);

        assertThat(packageMetadata.getPackageIdentifier(), is(MONITORING_SERVICE_PKG_ID));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(MONITORING_SERVICE_PKG_VERSION)));
    }

    private static Map<String, String> getExpectedDependencies(Semver version) {
        return new HashMap<String, String>() {{
            put("Log", version.toString());
            put("Cool-Database", version.toString());
        }};
    }
}
