/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.plugins.impl;

import com.aws.iot.evergreen.packagemanager.TestHelper;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStoreDeprecated;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocalPackageStoreDeprecatedTests {

    private LocalPackageStoreDeprecated testPackageStore;
    private LocalPackageStoreDeprecated mockPackageStore;

    @BeforeEach
    public void initStores() throws URISyntaxException, IOException {
        testPackageStore = new LocalPackageStoreDeprecated(TestHelper.getPathForLocalTestCache());
        mockPackageStore = new LocalPackageStoreDeprecated(TestHelper.getPathForMockRepository());
    }

    @Test
    public void GIVEN_valid_package_recipe_WHEN_attempt_package_recipe_create_THEN_valid_package_recipe_created()
            throws IOException, URISyntaxException, PackagingException {
        String recipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        Package testPkg = TestHelper.getPackageObject(recipeContents);
        testPackageStore.copyPackageArtifactsToPath(testPkg, TestHelper.getPathForLocalWorkingDirectory());
        Path expectedOutPath =
                TestHelper.getPathForLocalWorkingDirectory().resolve(TestHelper.MONITORING_SERVICE_PACKAGE_NAME)
                        .resolve("1.0.0");
        assertTrue(Files.exists(expectedOutPath));
        assertTrue(Files.exists(expectedOutPath.resolve("monitor_artifact_100.txt")));
    }

    @Test
    public void GIVEN_package_multiple_versions_WHEN_attempt_get_latest_version_THEN_latest_is_returned()
            throws UnexpectedPackagingException {
        Optional<Semver> latestVer = mockPackageStore.getLatestPackageVersionIfExists(TestHelper.LOG_PACKAGE_NAME);
        assertTrue(latestVer.isPresent());
        assertEquals(new Semver("2.0.0", Semver.SemverType.NPM), latestVer.get());
    }

    @Test
    public void GIVEN_package_name_version_WHEN_attempt_recipe_when_it_exists_THEN_recipe_is_returned()
            throws IOException, URISyntaxException, PackagingException {
        Optional<Package> recipe =
                mockPackageStore.getPackage(TestHelper.LOG_PACKAGE_NAME, new Semver("2.0.0", Semver.SemverType.NPM));
        assertTrue(recipe.isPresent());

        String expectedRecipeStr = TestHelper.getPackageRecipeFromMockRepository(TestHelper.LOG_PACKAGE_NAME, "2.0.0");
        Package expectedRecipe = TestHelper.getPackageObject(expectedRecipeStr);
        assertEquals(expectedRecipe, recipe.get());
    }

    @Test
    public void GIVEN_package_name_version_WHEN_attempt_recipe_when_doesnt_exist_THEN_nothing_returned()
            throws IOException, PackagingException {
        Optional<Package> recipe =
                mockPackageStore.getPackage(TestHelper.LOG_PACKAGE_NAME, new Semver("3.0.0", Semver.SemverType.NPM));
        assertFalse(recipe.isPresent());
    }

    @Test
    public void GIVEN_valid_package_recipe_WHEN_attempt_cache_THEN_artifacts_cached()
            throws IOException, URISyntaxException, PackagingException {
        String recipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        Package testPkg = TestHelper.getPackageObject(recipeContents);
        testPackageStore.cachePackageRecipeAndArtifacts(testPkg, recipeContents);
        Path expectedOutPath = TestHelper.getPathForLocalTestCache().resolve(TestHelper.MONITORING_SERVICE_PACKAGE_NAME)
                .resolve("1.0.0");
        assertTrue(Files.exists(expectedOutPath));
        assertTrue(Files.exists(expectedOutPath.resolve("monitor_artifact_100.txt")));
    }
}
