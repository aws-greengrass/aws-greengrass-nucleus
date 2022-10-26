/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.componentmanager.models.RecipeMetadata;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Digest;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.SerializerFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.aws.greengrass.helper.PreloadComponentStoreHelper.getHashFromComponentName;
import static com.aws.greengrass.helper.PreloadComponentStoreHelper.getRecipeStorageFilenameFromTestSource;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every test in ComponentStoreTest start with a new and clean package store by creating a temp folder. It pre loads
 * files from its test resource folder if it needs to mock some recipe/artifact. It doesn't and shouldn't use or assume
 * any static folder directly as package store. The package store folder is deleted after each test.
 */
@ExtendWith({GGExtension.class})
class ComponentStoreTest {
    private static final String MONITORING_SERVICE_PKG_NAME = "MonitoringService";
    private static final Semver MONITORING_SERVICE_PKG_VERSION = new Semver("1.0.0", Semver.SemverType.NPM);
    private static final ComponentIdentifier MONITORING_SERVICE_PKG_ID =
            new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, MONITORING_SERVICE_PKG_VERSION);
    private static final String MONITORING_SERVICE_PKG_ARTIFACT_NAME = "monitor_artifact_100.txt";
    public static final String MONITORING_SERVICE_PKG_RECIPE_FILE_NAME = "MonitoringService-1.0.0.yaml";
    public static final String MONITORING_SERVICE_PKG_RECIPE_METADATA_FILE_NAME =
            "MonitoringService@1.0.0.metadata.json";

    private static Path RECIPE_RESOURCE_PATH;
    private static Path ARTIFACT_RESOURCE_PATH;
    private static Path RECIPE_METADATA_RESOURCE_PATH;

    static {
        try {
            RECIPE_RESOURCE_PATH = Paths.get(ComponentStoreTest.class.getResource("recipes").toURI());
            ARTIFACT_RESOURCE_PATH = Paths.get(ComponentStoreTest.class.getResource("test_packages").toURI());
            RECIPE_METADATA_RESOURCE_PATH =
                    Paths.get(ComponentStoreTest.class.getResource("test_recipe_metadata_files").toURI());
        } catch (URISyntaxException ignore) {
        }
    }

    private ComponentStore componentStore;
    private PlatformResolver platformResolver;
    private RecipeLoader recipeLoader;
    private Path recipeDirectory;

    private Path artifactDirectory;

    private Path artifactsUnpackDirectory;

    @TempDir
    Path packageStoreRootPath;
    private NucleusPaths nucleusPaths;

    @BeforeEach
    void beforeEach() throws IOException {
        platformResolver = new PlatformResolver(null);
        recipeLoader = new RecipeLoader(platformResolver);

        nucleusPaths = new NucleusPaths();
        nucleusPaths.setComponentStorePath(packageStoreRootPath);
        componentStore = new ComponentStore(nucleusPaths, platformResolver, recipeLoader);
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
    void GIVEN_a_recipe_not_exists_when_saveComponentRecipe_THEN_recipe_file_created() throws Exception {
        // GIVEN
        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe recipe =
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020).componentName("MonitoringService")
                        .componentVersion(new Semver("1.0.0")).componentDescription("a monitor service").build();

        ComponentIdentifier componentIdentifier = new ComponentIdentifier("MonitoringService", new Semver("1.0.0"));

        File expectedRecipeFile = getExpectedRecipeFile(componentIdentifier);
        assertThat(expectedRecipeFile, not(anExistingFile()));

        // WHEN
        String returnedSavedContent = componentStore.saveComponentRecipe(recipe);

        // THEN
        assertThat(expectedRecipeFile, anExistingFile());
        String fileContent = new String(Files.readAllBytes(expectedRecipeFile.toPath()), StandardCharsets.UTF_8);
        assertThat(returnedSavedContent, is(fileContent));

        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe savedRecipe =
                com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer()
                        .readValue(fileContent, com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.class);
        assertThat(savedRecipe, is(recipe));
    }

    @Test
    void GIVEN_a_recipe_exists_when_saveComponentRecipe_THEN_recipe_file_is_updated() throws Exception {
        // GIVEN
        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe recipe =
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020).componentName("MonitoringService")
                        .componentVersion(new Semver("1.0.0")).componentDescription("a monitor service").build();

        ComponentIdentifier componentIdentifier = new ComponentIdentifier("MonitoringService", new Semver("1.0.0"));

        File expectedRecipeFile = getExpectedRecipeFile(componentIdentifier);

        assertThat(expectedRecipeFile, not(anExistingFile()));
        String oldContent = "old content that will be replaced";
        FileUtils.writeStringToFile(expectedRecipeFile, oldContent);

        assertThat(expectedRecipeFile, is(anExistingFile()));
        String fileContent = new String(Files.readAllBytes(expectedRecipeFile.toPath()));
        assertThat(fileContent, is(equalTo(oldContent)));

        // WHEN
        String returnedSavedContent = componentStore.saveComponentRecipe(recipe);

        // THEN
        fileContent = new String(Files.readAllBytes(expectedRecipeFile.toPath()), StandardCharsets.UTF_8);
        assertThat(returnedSavedContent, is(fileContent));
        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe savedRecipe =
                com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer()
                        .readValue(fileContent, com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.class);
        assertThat(savedRecipe, is(recipe));
    }

    @Test
    void GIVEN_a_recipe_exists_with_same_content_when_saveComponentRecipe_THEN_recipe_file_is_not_updated() throws Exception {
        // GIVEN
        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe recipe =
                com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.builder()
                        .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020).componentName("MonitoringService")
                        .componentVersion(new Semver("1.0.0")).componentDescription("a monitor service").build();

        String recipeString =
                com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer().writeValueAsString(recipe);

        ComponentIdentifier componentIdentifier = new ComponentIdentifier("MonitoringService", new Semver("1.0.0"));

        File expectedRecipeFile = getExpectedRecipeFile(componentIdentifier);

        assertThat(expectedRecipeFile, not(anExistingFile()));
        FileUtils.writeStringToFile(expectedRecipeFile, recipeString);

        assertThat(expectedRecipeFile, is(anExistingFile()));
        long modifiedTime = expectedRecipeFile.lastModified();

        // WHEN
        String returnedSavedContent = componentStore.saveComponentRecipe(recipe);

        // THEN
        assertThat(returnedSavedContent, is(recipeString));
        assertThat(expectedRecipeFile.lastModified(), is(modifiedTime));    // not modified during saveComponentRecipe
    }

    @Test
    void GIVEN_a_recipe_not_exists_when_savePackageRecipe_THEN_recipe_file_created() throws Exception {
        // GIVEN
        String recipeContent = "recipeContent";

        ComponentIdentifier componentIdentifier = new ComponentIdentifier("MonitoringService", new Semver("1.0.0"));

        File expectedRecipeFile = getExpectedRecipeFile(componentIdentifier);
        assertThat(expectedRecipeFile, not(anExistingFile()));

        // WHEN
        componentStore.savePackageRecipe(componentIdentifier, recipeContent);

        // THEN
        assertThat(getExpectedRecipeFile(componentIdentifier), anExistingFile());
        String fileContent = new String(Files.readAllBytes(expectedRecipeFile.toPath()));
        assertThat(fileContent, is(equalTo(recipeContent)));
    }

    private File getExpectedRecipeFile(ComponentIdentifier componentIdentifier) {
        String expectedFilename =
                String.format("%s@%s.recipe.yaml", getHashFromComponentName(componentIdentifier.getName()),
                        componentIdentifier.getVersion());
        return recipeDirectory.resolve(expectedFilename).toFile();
    }

    @Test
    void GIVEN_a_recipe_exists_when_savePackageRecipe_THEN_recipe_file_is_updated() throws Exception {
        // GIVEN
        String recipeContent = "recipeContent";

        ComponentIdentifier componentIdentifier = new ComponentIdentifier("MonitoringService", new Semver("1.0.0"));

        File expectedRecipeFile = getExpectedRecipeFile(componentIdentifier);

        assertThat(expectedRecipeFile, not(anExistingFile()));
        FileUtils.writeStringToFile(expectedRecipeFile, "old content that will be replaced");

        assertThat(expectedRecipeFile, is(anExistingFile()));

        // WHEN
        componentStore.savePackageRecipe(componentIdentifier, recipeContent);

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

        ComponentRecipe expectedRecipe = recipeLoader.loadFromFile(new String(Files.readAllBytes(sourceRecipe))).get();
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

        assertTrue(componentStore
                .validateComponentRecipeDigest(MONITORING_SERVICE_PKG_ID, Digest.calculate(recipeString)));

        assertFalse(componentStore
                .validateComponentRecipeDigest(MONITORING_SERVICE_PKG_ID, Digest.calculate("random String")));

        ComponentIdentifier nonExistentComponent =
                new ComponentIdentifier(MONITORING_SERVICE_PKG_NAME, new Semver("5.0.0"));
        assertFalse(componentStore.validateComponentRecipeDigest(nonExistentComponent, Digest.calculate(recipeString)));

        preloadEmptyRecipeFileFromTestResource();
        ComponentIdentifier emptyRecipeComponent =
                new ComponentIdentifier("EmptyRecipe", new Semver("1.0.0"));
        assertFalse(componentStore.validateComponentRecipeDigest(emptyRecipeComponent, Digest.calculate(recipeString)));
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

        ComponentRecipe expectedRecipe = recipeLoader.loadFromFile(new String(Files.readAllBytes(sourceRecipe))).get();
        assertThat(componentRecipe, equalTo(expectedRecipe));
    }

    @Test
    void GIVEN_a_recipe_does_not_exist_WHEN_getPackageRecipe_THEN_loading_exception_is_thrown() throws Exception {
        assertThrows(PackageLoadingException.class, () -> componentStore.getPackageRecipe(MONITORING_SERVICE_PKG_ID));
    }

    @Test
    void GIVEN_a_recipe_exists_WHEN_getPackageMetadata_then_return_it() throws Exception {
        // GIVEN
        preloadRecipeFileFromTestResource(MONITORING_SERVICE_PKG_RECIPE_FILE_NAME);

        // WHEN
        ComponentMetadata componentMetadata = componentStore.getPackageMetadata(MONITORING_SERVICE_PKG_ID);

        // THEN
        assertThat(componentMetadata.getComponentIdentifier(), is(MONITORING_SERVICE_PKG_ID));
        assertThat(componentMetadata.getDependencies(),
                is(getExpectedDependencies(Requirement.build(MONITORING_SERVICE_PKG_VERSION))));
    }

    @Test
    void GIVEN_pre_loaded_package_versions_WHEN_find_best_available_version_THEN_return_satisfied_version()
            throws Exception {
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
        assertThat(componentIdentifierOptional.get(),
                is(new ComponentIdentifier("MonitoringService", new Semver("1.1.0"))));

        // WHEN
        requirement = Requirement.buildNPM("^2.0");
        componentIdentifierOptional =
                componentStore.findBestMatchAvailableComponent(MONITORING_SERVICE_PKG_NAME, requirement);

        // THEN
        assertThat(componentIdentifierOptional.get(),
                is(new ComponentIdentifier("MonitoringService", new Semver("2.0.0"))));

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
        preloadRecipeMetadataFileFromTestResource(MONITORING_SERVICE_PKG_RECIPE_METADATA_FILE_NAME);

        File expectedRecipeFile = getExpectedRecipeFile(MONITORING_SERVICE_PKG_ID);
        File expectedArtifactFile = componentStore.resolveArtifactDirectoryPath(MONITORING_SERVICE_PKG_ID)
                .resolve(MONITORING_SERVICE_PKG_ARTIFACT_NAME).toFile();
        File expectedRecipeMetadataFile =
                getExpectedRecipeMetadataFile(MONITORING_SERVICE_PKG_NAME, MONITORING_SERVICE_PKG_VERSION.getValue());

        assertThat(expectedRecipeFile, anExistingFile());
        assertThat(expectedArtifactFile, anExistingFile());
        assertThat(expectedRecipeMetadataFile, anExistingFile());

        componentStore.deleteComponent(MONITORING_SERVICE_PKG_ID);

        assertThat(expectedRecipeFile, not(anExistingFile()));
        assertThat(expectedArtifactFile, not(anExistingFile()));
        assertThat(expectedRecipeMetadataFile, not(anExistingFile()));
    }

    @Test
    void GIVEN_artifacts_WHEN_list_by_artifact_THEN_result_is_correct() throws Exception {
        Set<ComponentIdentifier> mockComponents = new HashSet<>(
                Arrays.asList(new ComponentIdentifier("Mock1", new Semver("1.1.0")),
                        new ComponentIdentifier("Mock1", new Semver("1.2.0")),
                        new ComponentIdentifier("Mock2", new Semver("2.1.0")),
                        new ComponentIdentifier("Mock3", new Semver("3.1.0")),
                        new ComponentIdentifier("Mock3", new Semver("3.2.0"))));

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

    private void preloadRecipeFileFromTestResource(String recipeFileName) throws Exception {
        String destinationFilename = getRecipeStorageFilenameFromTestSource(recipeFileName);

        Path destinationRecipe = recipeDirectory.resolve(destinationFilename);

        Files.copy(RECIPE_RESOURCE_PATH.resolve(recipeFileName), destinationRecipe);
    }

    private void preloadEmptyRecipeFileFromTestResource() throws Exception {
        String destinationFilename = getRecipeStorageFilenameFromTestSource("EmptyRecipe-1.0.0.yaml");
        Path destinationRecipe = recipeDirectory.resolve(destinationFilename);
        Files.createFile(destinationRecipe);
    }

    private void preloadArtifactFileFromTestResouce(ComponentIdentifier pkgId, String artFileName)
            throws IOException, PackageLoadingException {
        Path sourceArtFile = ARTIFACT_RESOURCE_PATH.resolve(String.format("%s-%s", pkgId.getName(), pkgId.getVersion()))
                .resolve(artFileName);
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

    @Test
    void GIVEN_no_existing_metadata_file_WHEN_saveRecipeMetadata_THEN_file_is_written_with_right_content()
            throws Exception {
        // GIVEN
        String componentName = "HelloWorld";
        String version = "1.0.0";
        File expectedRecipeMetadataFile = getExpectedRecipeMetadataFile(componentName, version);

        assertThat(expectedRecipeMetadataFile, not(anExistingFile()));

        String testArn = "testArn";

        // WHEN
        componentStore.saveRecipeMetadata(new ComponentIdentifier(componentName, new Semver(version)),
                new RecipeMetadata(testArn));

        // THEN
        assertThat(expectedRecipeMetadataFile, is(anExistingFile()));

        String expectedContent =
                SerializerFactory.getFailSafeJsonObjectMapper().writeValueAsString(new RecipeMetadata(testArn));
        String actualContent = new String(Files.readAllBytes(expectedRecipeMetadataFile.toPath()));
        assertThat(actualContent, is(equalTo(expectedContent)));
    }

    @Test
    void GIVEN_existing_metadata_file_WHEN_saveRecipeMetadata_THEN_file_is_written_with_right_content()
            throws Exception {
        // GIVEN
        preloadRecipeMetadataFileFromTestResource("HelloWorld@1.0.0.metadata.json");

        String componentName = "HelloWorld";
        String version = "1.0.0";
        File expectedRecipeMetadataFile = getExpectedRecipeMetadataFile(componentName, version);

        assertThat(expectedRecipeMetadataFile, is(anExistingFile()));

        String updatedArn = "updatedArn";

        // WHEN
        componentStore.saveRecipeMetadata(new ComponentIdentifier(componentName, new Semver(version)),
                new RecipeMetadata(updatedArn));

        // THEN
        assertThat(expectedRecipeMetadataFile, is(anExistingFile()));

        String expectedContent =
                SerializerFactory.getFailSafeJsonObjectMapper().writeValueAsString(new RecipeMetadata(updatedArn));
        String actualContent = new String(Files.readAllBytes(expectedRecipeMetadataFile.toPath()));
        assertThat(actualContent, is(equalTo(expectedContent)));
    }

    private File getExpectedRecipeMetadataFile(String componentName, String componentVersion) {
        String hash = getHashFromComponentName(componentName);

        String expectedRecipeMetadataFileName =
                String.format("%s@%s.metadata.json", hash, componentVersion); // {hash}@1.0.0.metadata.json

        return recipeDirectory.resolve(expectedRecipeMetadataFileName).toFile();
    }

    @Test
    void GIVEN_a_valid_metadata_file_WHEN_getRecipeMetadata_THEN_return() throws Exception {
        preloadRecipeMetadataFileFromTestResource("HelloWorld@1.0.0.metadata.json");

        String expectedArn = "testArn"; // defined in HelloWorld@1.0.0.metadata.json
        RecipeMetadata recipeMetadata =
                componentStore.getRecipeMetadata(new ComponentIdentifier("HelloWorld", new Semver("1.0.0")));
        assertThat(recipeMetadata.getComponentVersionArn(), equalTo(expectedArn));
    }

    @Test
    void GIVEN_a_metadata_file_has_unknown_fields_WHEN_getRecipeMetadata_THEN_arn_can_still_be_returned()
            throws Exception {
        preloadRecipeMetadataFileFromTestResource("HelloWorld@0.0.0-test-unknown-fields.metadata.json");

        String expectedArn = "testArn"; // defined in HelloWorld@0.0.0-test-unknown-fields.metadata.json

        RecipeMetadata recipeMetadata = componentStore
                .getRecipeMetadata(new ComponentIdentifier("HelloWorld", new Semver("0.0.0-test-unknown-fields")));
        assertThat(recipeMetadata.getComponentVersionArn(), equalTo(expectedArn));
    }

    @Test
    void GIVEN_a_non_existing_metadata_file_WHEN_getRecipeMetadata_THEN_throws_PackageLoadingException() {
        assertThrows(PackageLoadingException.class,
                () -> componentStore.getRecipeMetadata(new ComponentIdentifier("HelloWorld", new Semver("0.0.0"))));
    }

    @Test
    void GIVEN_a_corrupted_metadata_file_WHEN_getRecipeMetadata_THEN_throws_PackageLoadingException(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, JsonParseException.class);   // ignore exception error log

        preloadRecipeMetadataFileFromTestResource("HelloWorld@0.0.0-test-corrupted.metadata.json");

        assertThrows(PackageLoadingException.class, () -> componentStore
                .getRecipeMetadata(new ComponentIdentifier("HelloWorld", new Semver("0.0.0-test-corrupted"))));
    }

    private void preloadRecipeMetadataFileFromTestResource(String fileName) throws Exception {
        Path sourceRecipe = RECIPE_METADATA_RESOURCE_PATH.resolve(fileName);
        String componentName = fileName.split("@")[0];

        String hash = getHashFromComponentName(componentName);

        String targetRecipeMetadataFileName = fileName.replace(componentName, hash);    // {hash}@1.0.0.metadata.json

        Path destinationRecipe = recipeDirectory.resolve(targetRecipeMetadataFileName);

        Files.copy(sourceRecipe, destinationRecipe);
    }

    private static Map<String, String> getExpectedDependencies(Requirement versionRequirement) {
        return new HashMap<String, String>() {{
            put(ComponentTestResourceHelper.LOG_PACKAGE_NAME, versionRequirement.toString());
            put(ComponentTestResourceHelper.COOL_DB_PACKAGE_NAME, versionRequirement.toString());
        }};
    }
}
