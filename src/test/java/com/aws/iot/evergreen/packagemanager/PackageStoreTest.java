/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.aws.iot.evergreen.packagemanager.TestHelper.COOL_DB_PACKAGE_NAME;
import static com.aws.iot.evergreen.packagemanager.TestHelper.LOG_PACKAGE_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.collection.IsIterableWithSize.iterableWithSize;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every test in PackageStoreTest start with a new and clean package store by creating a temp folder. It pre loads
 * files from its test resource folder if it needs to mock some recipe/artifact. It doesn't and shouldn't use or assume
 * any static folder directly as package store. The package store folder is deleted after each test.
 */
@ExtendWith({EGExtension.class})
class PackageStoreTest {
    private static final ObjectMapper RECIPE_SERIALIZER = SerializerFactory.getRecipeSerializer();

    private static final String MONITORING_SERVICE_PKG_NAME = "MonitoringService";
    private static final Semver MONITORING_SERVICE_PKG_VERSION = new Semver("1.0.0", Semver.SemverType.NPM);
    private static final PackageIdentifier MONITORING_SERVICE_PKG_ID =
            new PackageIdentifier(MONITORING_SERVICE_PKG_NAME, MONITORING_SERVICE_PKG_VERSION);

    private static Path RECIPE_RESOURCE_PATH;

    static {
        try {
            RECIPE_RESOURCE_PATH = Paths.get(PackageStoreTest.class.getResource("recipes").toURI());
        } catch (URISyntaxException ignore) {
        }
    }

    private PackageStore packageStore;

    private Path recipeDirectory;

    private Path artifactDirectory;

    private Path artifactsUnpackDirectory;

    @TempDir
    Path packageStoreRootPath;

    @BeforeEach
    void beforeEach() throws PackagingException {
        packageStore = new PackageStore(packageStoreRootPath.toAbsolutePath());
        recipeDirectory = packageStoreRootPath.resolve("recipes");
        artifactDirectory = packageStoreRootPath.resolve("artifacts");
        artifactsUnpackDirectory = packageStoreRootPath.resolve("artifacts-unpack");
    }

    @Test
    void WHEN_PackageStore_is_initialized_THEN_recipe_and_artifact_folders_created() {
        assertThat(recipeDirectory.toFile(), anExistingDirectory());
        assertThat(artifactDirectory.toFile(), anExistingDirectory());
        assertThat(artifactsUnpackDirectory.toFile(), anExistingDirectory());
    }

    @Test
    void GIVEN_a_recipe_not_exists_when_savePackageRecipe_THEN_recipe_file_created()
            throws IOException, PackageLoadingException {
        // GIVEN
        String fileName = "MonitoringService-1.0.0.yaml";
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);
        PackageRecipe recipe =
                RECIPE_SERIALIZER.readValue(new String(Files.readAllBytes(sourceRecipe)), PackageRecipe.class);


        File expectedRecipeFile = recipeDirectory.resolve(fileName).toFile();
        assertThat(expectedRecipeFile, not(anExistingFile()));

        // WHEN
        packageStore.savePackageRecipe(recipe);

        // THEN
        assertThat(expectedRecipeFile, anExistingFile());
        String fileContent = new String(Files.readAllBytes(expectedRecipeFile.toPath()));
        assertThat(fileContent, is(equalTo(RECIPE_SERIALIZER.writeValueAsString(recipe))));
    }

    @Test
    void GIVEN_a_recipe_exists_when_savePackageRecipe_THEN_recipe_file_is_updated()
            throws IOException, PackageLoadingException {
        // GIVEN
        String fileName = "MonitoringService-1.0.0.yaml";
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);
        PackageRecipe recipe =
                RECIPE_SERIALIZER.readValue(new String(Files.readAllBytes(sourceRecipe)), PackageRecipe.class);


        File expectedRecipeFile = recipeDirectory.resolve(fileName).toFile();

        assertThat(expectedRecipeFile, not(anExistingFile()));

        boolean fileCreated = expectedRecipeFile.createNewFile();
        assertTrue(fileCreated, "Failed to create empty recipe file.");

        assertThat(expectedRecipeFile, is(anExistingFile()));

        // WHEN
        packageStore.savePackageRecipe(recipe);

        // THEN
        String fileContent = new String(Files.readAllBytes(expectedRecipeFile.toPath()));
        assertThat(fileContent, is(equalTo(RECIPE_SERIALIZER.writeValueAsString(recipe))));
    }


    @Test
    void GIVEN_a_recipe_exists_WHEN_findPackageRecipe_THEN_return_it() throws Exception {
        // GIVEN
        String fileName = "MonitoringService-1.0.0.yaml";
        preloadRecipeFileFromTestResource(fileName);

        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);

        // WHEN
        Optional<PackageRecipe> optionalPackageRecipe = packageStore.findPackageRecipe(MONITORING_SERVICE_PKG_ID);

        // THEN
        assertTrue(optionalPackageRecipe.isPresent());

        PackageRecipe expectedRecipe =
                RECIPE_SERIALIZER.readValue(new String(Files.readAllBytes(sourceRecipe)), PackageRecipe.class);
        assertThat(optionalPackageRecipe.get(), equalTo(expectedRecipe));
    }

    @Test
    void WHEN_resolve_setup_upack_dir_THEN_dir_created() throws Exception {
        // WHEN
        Path path = packageStore.resolveAndSetupArtifactsUnpackDirectory(MONITORING_SERVICE_PKG_ID);
        ///var/folders/37/0h21kkrj1fl9qn472lr2r15rcw2086/T/junit2770550780637482865/artifacts-unpack/MonitoringService/1.0.0
        //THEN
        assertEquals(path, packageStoreRootPath.resolve("artifacts-unpack/MonitoringService/1.0.0"));
        assertThat(path.toFile(), anExistingDirectory());
    }


    @Test
    void GIVEN_a_recipe_does_not_exist_WHEN_findPackageRecipe_THEN_empty_is_returned() throws Exception {
        // WHEN
        Optional<PackageRecipe> optionalPackageRecipe = packageStore.findPackageRecipe(MONITORING_SERVICE_PKG_ID);

        // THEN
        assertFalse(optionalPackageRecipe.isPresent());
    }

    @Test
    void GIVEN_an_invalid_recipe_exists_WHEN_findPackageRecipe_THEN_loading_exception_is_thrown() throws Exception {
        // GIVEN
        String fileName = "InvalidRecipe-1.0.0.yaml";
        preloadRecipeFileFromTestResource(fileName);

        // WHEN
        // THEN
        assertThrows(PackageLoadingException.class,
                () -> packageStore.findPackageRecipe(new PackageIdentifier("InvalidRecipe", new Semver("1.0.0"))));
    }

    @Test
    void GIVEN_a_recipe_exists_WHEN_getPackageRecipe_THEN_return_it() throws Exception {
        // GIVEN
        String fileName = "MonitoringService-1.0.0.yaml";
        preloadRecipeFileFromTestResource(fileName);

        // WHEN
        PackageRecipe packageRecipe = packageStore.getPackageRecipe(MONITORING_SERVICE_PKG_ID);

        // THEN
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);

        PackageRecipe expectedRecipe =
                RECIPE_SERIALIZER.readValue(new String(Files.readAllBytes(sourceRecipe)), PackageRecipe.class);
        assertThat(packageRecipe, equalTo(expectedRecipe));
    }

    @Test
    void GIVEN_a_recipe_does_not_exist_WHEN_getPackageRecipe_THEN_loading_exception_is_thrown() throws Exception {
        assertThrows(PackageLoadingException.class, () -> packageStore.getPackageRecipe(MONITORING_SERVICE_PKG_ID));
    }

    @Test
    void GIVEN_a_recipe_exists_WHEN_getPackageMetadata_then_return_it() throws PackagingException, IOException {
        // GIVEN
        String fileName = "MonitoringService-1.0.0.yaml";

        preloadRecipeFileFromTestResource(fileName);

        // WHEN
        PackageMetadata packageMetadata = packageStore.getPackageMetadata(MONITORING_SERVICE_PKG_ID);

        // THEN
        assertThat(packageMetadata.getPackageIdentifier(), is(MONITORING_SERVICE_PKG_ID));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(MONITORING_SERVICE_PKG_VERSION)));
    }

    @Test
    void GIVEN_pre_loaded_package_versions_WHEN_listAvailablePackageMetadata_THEN_return_satisfiedVersion()
            throws IOException, PackagingException {
        // GIVEN
        preloadRecipeFileFromTestResource("MonitoringService-1.0.0.yaml");
        preloadRecipeFileFromTestResource("MonitoringService-1.1.0.yaml");
        preloadRecipeFileFromTestResource("MonitoringService-2.0.0.yaml");
        preloadRecipeFileFromTestResource("MonitoringService-3.0.0.yaml");
        preloadRecipeFileFromTestResource("Log-1.0.0.yaml");

        // WHEN
        Requirement requirement = Requirement.buildNPM(">=1.0.0 <2.0.0");
        List<PackageMetadata> packageMetadataList =
                packageStore.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        // expected return: MonitoringService 1.0.0 and 1.1.0
        assertThat(packageMetadataList, iterableWithSize(2));

        // 1.0.0
        PackageMetadata packageMetadata = packageMetadataList.get(0);
        assertThat(packageMetadata.getPackageIdentifier().getName(), is(MONITORING_SERVICE_PKG_NAME));
        assertThat(packageMetadata.getPackageIdentifier().getVersion(), is(new Semver("1.0.0")));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(new Semver("1.0.0"))));

        // 1.1.0
        packageMetadata = packageMetadataList.get(1);
        assertThat(packageMetadata.getPackageIdentifier().getName(), is(MONITORING_SERVICE_PKG_NAME));
        assertThat(packageMetadata.getPackageIdentifier().getVersion(), is(new Semver("1.1.0")));
        assertThat(packageMetadata.getDependencies(), is(getExpectedDependencies(new Semver("1.1.0"))));
    }

    private void preloadRecipeFileFromTestResource(String fileName) throws IOException {
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);

        Path destinationRecipe = recipeDirectory.resolve(fileName);

        Files.copy(sourceRecipe, destinationRecipe);
    }

    @Test
    void resolveArtifactDirectoryPath() {
        Path artifactPath = packageStore.resolveArtifactDirectoryPath(MONITORING_SERVICE_PKG_ID);

        Path expectedArtifactPath = artifactDirectory.resolve(MONITORING_SERVICE_PKG_ID.getName())
                .resolve(MONITORING_SERVICE_PKG_ID.getVersion().getValue());
        assertThat(artifactPath.toAbsolutePath(), is(equalTo(expectedArtifactPath)));
    }

    private static Map<String, String> getExpectedDependencies(Semver version) {
        return new HashMap<String, String>() {{
            put(LOG_PACKAGE_NAME, version.toString());
            put(COOL_DB_PACKAGE_NAME, version.toString());
        }};
    }
}
