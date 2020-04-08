package com.aws.iot.evergreen.packagemanager;


import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.plugins.ArtifactDownloader;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageStoreTest {

    private final Path testCache = TestHelper.getPathForLocalTestCache();

    @InjectMocks
    private final PackageStore packageStore = new PackageStore(testCache);

    @Mock
    private ArtifactDownloader artifactDownloader;

    @Mock
    private GreengrassPackageServiceHelper packageServiceHelper;

    @AfterEach
    void cleanTestCache() throws Exception {
        TestHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_path_to_valid_package_recipe_WHEN_attempt_find_package_THEN_package_model_is_returned()
            throws Exception {
        Path recipePath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0")
                .resolve("recipe.yaml");
        Optional<Package> pkg = packageStore.findPackageRecipe(recipePath);
        assertThat(pkg.isPresent(), is(true));
    }

    @Test
    void GIVEN_path_to_invalid_package_recipe_WHEN_attempt_find_package_THEN_get_loading_exception() throws Exception {
        Path recipePath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0")
                .resolve("bad_recipe.yaml");

        assertThrows(PackageLoadingException.class, () -> packageStore.findPackageRecipe(recipePath));
    }

    @Test
    void GIVEN_invalid_path_to_package_recipe_WHEN_attempt_find_package_THEN_null_is_returned() throws Exception {
        Path recipePath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0")
                .resolve("not_exist_recipe.yaml");

        Optional<Package> pkg = packageStore.findPackageRecipe(recipePath);
        assertThat(pkg.isPresent(), is(false));
    }

    @Test
    void GIVEN_package_in_memory_WHEN_attempt_save_package_THEN_successfully_save_to_file() throws Exception {
        Path recipePath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0")
                .resolve("recipe.yaml");
        Package pkg = packageStore.findPackageRecipe(recipePath).get();

        Path saveToFile =
                testCache.resolve(String.format("%s-%s.yaml", TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0"));
        packageStore.savePackageToFile(pkg, saveToFile);

        Package savedPackage = packageStore.findPackageRecipe(saveToFile).get();
        assertThat(savedPackage, is(pkg));
    }

    @Test
    void GIVEN_artifact_list_empty_WHEN_attempt_download_artifact_THEN_do_nothing() throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0.0"), "CoolServiceARN");

        packageStore.downloadArtifactsIfNecessary(pkgId, Collections.emptyList());

        verify(artifactDownloader, never()).downloadToPath(any(), any(), any());
    }

    @Test
    void GIVEN_artifact_list_WHEN_attempt_download_artifact_THEN_invoke_downloader() throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0.0"), "CoolServiceARN");

        packageStore.downloadArtifactsIfNecessary(pkgId,
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

        assertThrows(PackageLoadingException.class, () -> packageStore
                .downloadArtifactsIfNecessary(pkgId, Collections.singletonList(new URI("docker:image1"))));
    }

    @Test
    void GIVEN_artifact_url_no_scheme_WHEN_attempt_download_THEN_throw_package_exception() {
        PackageIdentifier pkgId = new PackageIdentifier("CoolService", new Semver("1.0" + ".0"), "CoolServiceARN");

        assertThrows(PackageLoadingException.class,
                () -> packageStore.downloadArtifactsIfNecessary(pkgId, Collections.singletonList(new URI("binary1"))));
    }

    @Test
    void GIVEN_package_identifier_WHEN_request_to_prepare_package_THEN_task_succeed() throws Exception {
        PackageIdentifier pkgId = new PackageIdentifier("SomeService", new Semver("1.0.0"), "PackageARN");
        Path recipePath = TestHelper.getPathForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0")
                .resolve("recipe.yaml");
        Package pkg = packageStore.findPackageRecipe(recipePath).get();
        when(packageServiceHelper.downloadPackageRecipe(any())).thenReturn(pkg);
        List<CompletableFuture<Boolean>> futures = packageStore.preparePackages(Collections.singletonList(pkgId));

        assertThat(futures.size(), is(1));
        assertThat(futures.get(0).get(), is(true));

        verify(packageServiceHelper).downloadPackageRecipe(pkgId);
    }
}
