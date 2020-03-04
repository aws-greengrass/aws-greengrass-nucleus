/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.packagemanager.TestHelper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PackageTest {

    @Test
    public void GIVEN_valid_package_recipe_WHEN_attempt_package_recipe_create_THEN_valid_package_recipe_created()
            throws IOException, URISyntaxException {
        String recipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        Package testPkg = TestHelper.getPackageObject(recipeContents);
        assertEquals(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, testPkg.getPackageName());
        assertTrue(testPkg.getVersion().isEqualTo("1.0.0"));
        assertEquals("Me", testPkg.getPublisher());
        assertEquals(RecipeTemplateVersion.JAN_25_2020, testPkg.getRecipeTemplateVersion());
        assertEquals("2020-01-25", testPkg.getRecipeTemplateVersion().getRecipeTemplateVersion());
        assertNotNull(testPkg.getLifecycle());

        // TODO: Check for providers
        assertNotNull(testPkg.getArtifacts());

        // TODO: Check for dependency nodes
        assertNotNull(testPkg.getDependencyPackages());

        List<String> requiresList = testPkg.getRequires();
        assertTrue(requiresList.contains("homebrew"));

        Set<PackageParameter> paramList = testPkg.getPackageParameters();
        assertFalse(paramList.isEmpty());
        PackageParameter parameter = new PackageParameter("TestParam", "TestVal", "String");
        assertTrue(paramList.contains(parameter));
    }

    @Test
    public void GIVEN_valid_package_recipe_WHEN_compare_to_copy_THEN_compare_returns_equal()
            throws IOException, URISyntaxException {
        // All the asserts in this can be simplified to assertEquals/NotEquals. equals/hashcode used directly
        // for coverage of both

        // Packages are same
        String monitorServiceRecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        Package monitorServicePkg = TestHelper.getPackageObject(monitorServiceRecipeContents);
        Package monitorServicePkgCopy = TestHelper.getPackageObject(monitorServiceRecipeContents);

        assertTrue(monitorServicePkg.equals(monitorServicePkgCopy));
        assertTrue(monitorServicePkg.hashCode() == monitorServicePkgCopy.hashCode());
    }

    @Test
    public void GIVEN_valid_package_recipe_WHEN_compare_to_different_version_THEN_compare_returns_not_equal()
            throws IOException, URISyntaxException {
        String monitorServiceRecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        Package monitorServicePkg = TestHelper.getPackageObject(monitorServiceRecipeContents);

        // Same package different versions
        String monitorService11RecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0");
        Package monitorService11Pkg = TestHelper.getPackageObject(monitorService11RecipeContents);

        assertFalse(monitorServicePkg.equals(monitorService11Pkg));
        assertFalse(monitorServicePkg.hashCode() == monitorService11Pkg.hashCode());
    }

    @Test
    public void GIVEN_valid_package_recipe_WHEN_compare_to_different_recipe_THEN_compare_returns_not_equal()
            throws IOException, URISyntaxException {
        String monitorServiceRecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        Package monitorServicePkg = TestHelper.getPackageObject(monitorServiceRecipeContents);

        // Different packages
        String conveyorBeltRecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.CONVEYOR_BELT_PACKAGE_NAME, "1.0.0");
        Package conveyorBeltPkg = TestHelper.getPackageObject(conveyorBeltRecipeContents);

        assertFalse(monitorServicePkg.equals(conveyorBeltPkg));
        assertFalse(monitorServicePkg.hashCode() == conveyorBeltPkg.hashCode());

        // Input is not a package
        assertFalse(monitorServicePkg.equals(conveyorBeltRecipeContents));
    }

    @Test
    public void GIVEN_invalid_recipe_version_WHEN_try_create_package_recipe_THEN_throws_exception()
            throws IOException, URISyntaxException {
        String monitorServiceRecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.INVALID_VERSION_PACKAGE_NAME, "1.0.0");
        assertThrows(IOException.class, () -> TestHelper.getPackageObject(monitorServiceRecipeContents),
                "Expected IOException but didn't throw");
    }
}
