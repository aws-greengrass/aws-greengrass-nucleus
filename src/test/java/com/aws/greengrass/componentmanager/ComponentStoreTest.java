/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Digest;
import com.aws.greengrass.util.NucleusPaths;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.io.FileUtils;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * Every test in ComponentStoreTest start with a new and clean package store by creating a temp folder. It pre loads files
 * from its test resource folder if it needs to mock some recipe/artifact. It doesn't and shouldn't use or assume any
 * static folder directly as package store. The package store folder is deleted after each test.
 */
@ExtendWith({GGExtension.class})
class ComponentStoreTest {
    private static final String MONITORING_SERVICE_PKG_NAME = "MonitoringService";
    private static final Semver MONITORING_SERVICE_PKG_VERSION = new Semver("1.0.0", Semver.SemverType.NPM);
    private static final ComponentIdentifier MONITORING_SERVICE_PKG_ID =
            new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, MONITORING_SERVICE_PKG_VERSION);
    private static final String MONITORING_SERVICE_PKG_ARTIFACT_NAME = "monitor_artifact_100.txt";
    public static final String MONITORING_SERVICE_PKG_RECIPE_FILE_NAME = "MonitoringService-1.0.0.yaml";

    private static Path RECIPE_RESOURCE_PATH;
    private static Path ARTIFACT_RESOURCE_PATH;

    static {
        try {
            RECIPE_RESOURCE_PATH = Paths.get(ComponentStoreTest.class.getResource("recipes").toURI());
            ARTIFACT_RESOURCE_PATH = Paths.get(ComponentStoreTest.class.getResource("test_packages").toURI());
        } catch (URISyntaxException ignore) {
        }
    }

    private ComponentStore componentStore;

    private Path recipeDirectory;

    private Path artifactDirectory;

    private Path artifactsUnpackDirectory;

    @TempDir
    Path packageStoreRootPath;
    private NucleusPaths nucleusPaths;

    @BeforeEach
    void beforeEach() throws IOException {
        nucleusPaths = new NucleusPaths();
        nucleusPaths.setComponentStorePath(packageStoreRootPath);
        componentStore = new ComponentStore(nucleusPaths);
        recipeDirectory = packageStoreRootPath.resolve("recipes");
        artifactDirectory = packageStoreRootPath.resolve("artifacts");
        artifactsUnpackDirectory = packageStoreRootPath.resolve("artifacts-unarchived");
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
        String fileName = MONITORING_SERVICE_PKG_RECIPE_FILE_NAME;
        String recipeContent = "recipeContent";

        File expectedRecipeFile = recipeDirectory.resolve(fileName).toFile();
        assertThat(expectedRecipeFile, not(anExistingFile()));

        // WHEN
        componentStore
                .savePackageRecipe(new ComponentIdentifier("MonitoringService", new Semver("1.0.0")), recipeContent);

        // THEN
        assertThat(expectedRecipeFile, anExistingFile());
        String fileContent = new String(Files.readAllBytes(expectedRecipeFile.toPath()));
        assertThat(fileContent, is(equalTo(recipeContent)));
    }

    @Test
    void GIVEN_a_recipe_exists_when_savePackageRecipe_THEN_recipe_file_is_updated()
            throws IOException, PackageLoadingException {
        // GIVEN
        String fileName = MONITORING_SERVICE_PKG_RECIPE_FILE_NAME;
        String recipeContent = "recipeContent";


        File expectedRecipeFile = recipeDirectory.resolve(fileName).toFile();

        assertThat(expectedRecipeFile, not(anExistingFile()));
        FileUtils.writeStringToFile(expectedRecipeFile, "old content that will be replaced");

        assertThat(expectedRecipeFile, is(anExistingFile()));

        // WHEN
        componentStore
                .savePackageRecipe(new ComponentIdentifier("MonitoringService", new Semver("1.0.0")), recipeContent);

        // THEN
        String fileContent = new String(Files.readAllBytes(expectedRecipeFile.toPath()));
        assertThat(fileContent, is(equalTo(recipeContent)));
    }


    @Test
    void GIVEN_a_recipe_exists_WHEN_findPackageRecipe_THEN_return_it() throws Exception {
        // GIVEN
        preloadRecipeFileFromTestResource(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);

        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);

        // WHEN
        Optional<ComponentRecipe> optionalPackageRecipe = componentStore.findPackageRecipe(MONITORING_SERVICE_PKG_ID);

        // THEN
        assertTrue(optionalPackageRecipe.isPresent());

        ComponentRecipe expectedRecipe = RecipeLoader.loadFromFile(new String(Files.readAllBytes(sourceRecipe))).get();
        assertThat(optionalPackageRecipe.get(), equalTo(expectedRecipe));
    }

    @Test
    void WHEN_resolve_setup_upack_dir_THEN_dir_created() throws Exception {
        // WHEN
        Path path = nucleusPaths.unarchiveArtifactPath(MONITORING_SERVICE_PKG_ID);
        ///var/folders/37/0h21kkrj1fl9qn472lr2r15rcw2086/T/junit2770550780637482865/artifacts-unarchived/MonitoringService/1.0.0
        //THEN
        assertEquals(path, packageStoreRootPath.resolve("artifacts-unarchived/MonitoringService/1.0.0"));
        assertThat(path.toFile(), anExistingDirectory());
    }

    @Test
    void GIVEN_component_WHEN_validate_recipe_called_THEN_works() throws Exception {
        preloadRecipeFileFromTestResource(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);
        String recipeString = new String(Files.readAllBytes(sourceRecipe));

        assertTrue(componentStore.validateComponentRecipeDigest(
                MONITORING_SERVICE_PKG_ID, Digest.calculate(recipeString)));

        assertFalse(componentStore.validateComponentRecipeDigest(
                MONITORING_SERVICE_PKG_ID, Digest.calculate("random String")));

        ComponentIdentifier nonExistentComponent =
                new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("5.0.0"));
        assertFalse(componentStore.validateComponentRecipeDigest(
                nonExistentComponent, Digest.calculate(recipeString)));
    }

    @Test
    void GIVEN_a_recipe_does_not_exist_WHEN_findPackageRecipe_THEN_empty_is_returned() throws Exception {
        // WHEN
        Optional<ComponentRecipe> optionalPackageRecipe = componentStore.findPackageRecipe(MONITORING_SERVICE_PKG_ID);

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
                () -> componentStore.findPackageRecipe(new ComponentIdentifier("InvalidRecipe", new Semver("1.0.0"))));
    }

    @Test
    void GIVEN_a_recipe_exists_WHEN_getPackageRecipe_THEN_return_it() throws Exception {
        // GIVEN
        preloadRecipeFileFromTestResource(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);

        // WHEN
        ComponentRecipe componentRecipe = componentStore.getPackageRecipe(MONITORING_SERVICE_PKG_ID);

        // THEN
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);

        ComponentRecipe expectedRecipe = RecipeLoader.loadFromFile(new String(Files.readAllBytes(sourceRecipe))).get();
        assertThat(componentRecipe, equalTo(expectedRecipe));
    }

    @Test
    void GIVEN_a_recipe_does_not_exist_WHEN_getPackageRecipe_THEN_loading_exception_is_thrown() throws Exception {
        assertThrows(PackageLoadingException.class, () -> componentStore.getPackageRecipe(MONITORING_SERVICE_PKG_ID));
    }

    @Test
    void GIVEN_a_recipe_exists_WHEN_getPackageMetadata_then_return_it() throws PackagingException, IOException {
        // GIVEN
        preloadRecipeFileFromTestResource(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);

        // WHEN
        ComponentMetadata componentMetadata = componentStore.getPackageMetadata(MONITORING_SERVICE_PKG_ID);

        // THEN
        assertThat(componentMetadata.getComponentIdentifier(), is(MONITORING_SERVICE_PKG_ID));
        assertThat(componentMetadata.getDependencies(), is(getExpectedDependencies(Requirement.build(MONITORING_SERVICE_PKG_VERSION))));
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
        List<ComponentMetadata> componentMetadataList =
                componentStore.listAvailablePackageMetadata(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        // expected return: MonitoringService 1.0.0 and 1.1.0
        assertThat(componentMetadataList, iterableWithSize(2));

        // 1.1.0
        ComponentMetadata componentMetadata = componentMetadataList.get(0);
        assertThat(componentMetadata.getComponentIdentifier().getName(), is(MONITORING_SERVICE_PKG_NAME));
        assertThat(componentMetadata.getComponentIdentifier().getVersion(), is(new Semver("1.1.0")));
        assertThat(componentMetadata.getDependencies(), is(getExpectedDependencies(Requirement.buildNPM("1.1.0"))));

        // 1.0.0
        componentMetadata = componentMetadataList.get(1);
        assertThat(componentMetadata.getComponentIdentifier().getName(), is(MONITORING_SERVICE_PKG_NAME));
        assertThat(componentMetadata.getComponentIdentifier().getVersion(), is(new Semver("1.0.0")));
        assertThat(componentMetadata.getDependencies(), is(getExpectedDependencies(Requirement.buildNPM("1.0.0"))));
    }

    @Test
    void GIVEN_pre_loaded_package_versions_WHEN_find_best_available_version_THEN_return_satisfied_version()
            throws IOException, PackagingException {
        // GIVEN
        preloadRecipeFileFromTestResource("MonitoringService-1.0.0.yaml");
        preloadRecipeFileFromTestResource("MonitoringService-1.1.0.yaml");
        preloadRecipeFileFromTestResource("MonitoringService-2.0.0.yaml");
        preloadRecipeFileFromTestResource("MonitoringService-3.0.0.yaml");
        preloadRecipeFileFromTestResource("Log-1.0.0.yaml");

        // WHEN
        Requirement requirement = Requirement.buildNPM(">=1.0.0 <2.0.0");
        Optional<ComponentIdentifier> componentIdentifierOptional =
                componentStore.findBestMatchAvailableComponent(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        assertThat(componentIdentifierOptional.get(), is(new ComponentIdentifier("MonitoringService",
                new Semver("1.1.0"))));

        // WHEN
        requirement = Requirement.buildNPM("^2.0");
        componentIdentifierOptional =
                componentStore.findBestMatchAvailableComponent(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        assertThat(componentIdentifierOptional.get(), is(new ComponentIdentifier("MonitoringService",
                new Semver("2.0.0"))));

        // WHEN
        requirement = Requirement.buildNPM("^3.1");
        componentIdentifierOptional =
                componentStore.findBestMatchAvailableComponent(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        assertThat(componentIdentifierOptional.isPresent(), is(false));
    }

    @Test
    void GIVEN_recipe_and_artifact_exists_WHEN_delete_package_THEN_both_deleted() throws Exception {
        preloadRecipeFileFromTestResource(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);
        preloadArtifactFileFromTestResouce(MONITORING_SERVICE_PKG_ID, MONITORING_SERVICE_PKG_ARTIFACT_NAME);
        Path expectedRecipePath = recipeDirectory.resolve(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);
        Path expectedArtifactPath = componentStore.resolveArtifactDirectoryPath(MONITORING_SERVICE_PKG_ID)
                .resolve(MONITORING_SERVICE_PKG_ARTIFACT_NAME);
        assertTrue(Files.exists(expectedRecipePath));
        assertTrue(Files.exists(expectedArtifactPath));
        componentStore.deleteComponent(MONITORING_SERVICE_PKG_ID);
        assertFalse(Files.exists(expectedRecipePath));
        assertFalse(Files.exists(expectedArtifactPath));
    }


    @Test
    void GIVEN_artifacts_WHEN_list_by_artifact_THEN_result_is_correct() throws Exception {
        Set<ComponentIdentifier> mockComponents = new HashSet<>(Arrays.asList(
                new ComponentIdentifier("Mock1", new Semver("1.1.0")),
                new ComponentIdentifier("Mock1", new Semver("1.2.0")),
                new ComponentIdentifier("Mock2", new Semver("2.1.0")),
                new ComponentIdentifier("Mock3", new Semver("3.1.0")),
                new ComponentIdentifier("Mock3", new Semver("3.2.0"))
        ));

        // mock these artifact exist
        for (ComponentIdentifier mockComponent : mockComponents) {
            createEmptyArtifactDir(mockComponent);
        }

        Map<String, Set<String>> foundComponentVersions = componentStore.listAvailableComponentVersions();
        Set<ComponentIdentifier> foundComponents = new HashSet<>();
        for (Map.Entry<String, Set<String>> foundEntry : foundComponentVersions.entrySet()) {
            for (String version : foundEntry.getValue()) {
                foundComponents.add(new ComponentIdentifier(foundEntry.getKey(), new Semver(version)));
            }
        }
        assertEquals(mockComponents, foundComponents);
    }

    @Test
    void GIVEN_recipe_exists_WHEN_get_content_size_THEN_correct_value_returned() throws Exception {
        // test should start with empty package store so expect content size zero
        assertEquals(0, componentStore.getContentSize());

        // put in a recipe, should include that file size
        preloadRecipeFileFromTestResource(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);
        long recipeLength = RECIPE_RESOURCE_PATH.resolve(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME).toFile().length();
        assertEquals(recipeLength, componentStore.getContentSize());
    }

    private void preloadRecipeFileFromTestResource(String fileName) throws IOException {
        Path sourceRecipe = RECIPE_RESOURCE_PATH.resolve(fileName);

        Path destinationRecipe = recipeDirectory.resolve(fileName);

        Files.copy(sourceRecipe, destinationRecipe);
    }

    private void preloadArtifactFileFromTestResouce(ComponentIdentifier pkgId, String artFileName)
            throws IOException, PackageLoadingException {
        Path sourceArtFile = ARTIFACT_RESOURCE_PATH.resolve(String.format("%s-%s", pkgId.getName(),
                pkgId.getVersion())).resolve(artFileName);
        Path destArtFile = componentStore.resolveArtifactDirectoryPath(pkgId).resolve(artFileName);
        Files.createDirectories(destArtFile.getParent());
        Files.copy(sourceArtFile, destArtFile);
    }

    private void createEmptyArtifactDir(ComponentIdentifier pkgId) throws PackageLoadingException, IOException {
        Path artifactDir = componentStore.resolveArtifactDirectoryPath(pkgId);
        Files.createDirectories(artifactDir);
    }

    @Test
    void resolveArtifactDirectoryPath() throws PackageLoadingException {
        Path artifactPath = componentStore.resolveArtifactDirectoryPath(MONITORING_SERVICE_PKG_ID);

        Path expectedArtifactPath = artifactDirectory.resolve(MONITORING_SERVICE_PKG_ID.getName())
                                                     .resolve(MONITORING_SERVICE_PKG_ID.getVersion().getValue());
        assertThat(artifactPath.toAbsolutePath(), is(equalTo(expectedArtifactPath)));
    }

    private static Map<String, String> getExpectedDependencies(Requirement versionRequirement) {
        return new HashMap<String, String>() {{
            put(ComponentTestResourceHelper.LOG_PACKAGE_NAME, versionRequirement.toString());
            put(ComponentTestResourceHelper.COOL_DB_PACKAGE_NAME, versionRequirement.toString());
        }};
    }
}
