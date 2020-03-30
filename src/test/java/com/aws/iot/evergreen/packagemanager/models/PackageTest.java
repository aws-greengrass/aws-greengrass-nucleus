/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.config.PlatformResolver;
import com.aws.iot.evergreen.packagemanager.TestHelper;
import org.hamcrest.collection.IsCollectionWithSize;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PackageTest {

    @BeforeAll
    static void beforeAll() throws Exception {
        Field ranksField = PlatformResolver.class.getDeclaredField("RANKS");
        ranksField.setAccessible(true);

        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(ranksField, ranksField.getModifiers() & ~Modifier.FINAL);

        ranksField.set(null, new HashMap<String, Integer>() {{
            put("macos", 99);
            put("linux", 1);
        }});
    }

    @Test
    void GIVEN_valid_package_recipe_WHEN_attempt_package_recipe_create_THEN_valid_package_recipe_created()
            throws IOException, URISyntaxException {
        String recipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        Package testPkg = TestHelper.getPackageObject(recipeContents);
        assertThat(testPkg.getPackageName(), is(TestHelper.MONITORING_SERVICE_PACKAGE_NAME));
        assertThat(testPkg.getVersion().getValue(), is("1.0.0"));
        assertThat(testPkg.getPublisher(), is("Me"));
        assertThat(testPkg.getRecipeTemplateVersion(), is(RecipeTemplateVersion.JAN_25_2020));
        assertThat(testPkg.getRecipeTemplateVersion().getRecipeTemplateVersion(), is("2020-01-25"));

        assertThat(testPkg.getLifecycle().size(), is(2));
        assertThat(testPkg.getLifecycle(), IsMapContaining.hasKey("run"));

        // TODO: Check for providers
        assertThat(testPkg.getArtifacts(), IsCollectionWithSize.hasSize(1));

        assertThat(testPkg.getDependencies().size(), is(1));
        assertThat(testPkg.getDependencies(), IsMapContaining.hasEntry("mac-log", "1.0"));

        assertThat(testPkg.getRequires(), IsCollectionWithSize.hasSize(1));
        assertThat(testPkg.getRequires().get(0), is("homebrew"));

        Set<PackageParameter> paramList = testPkg.getPackageParameters();
        assertThat(paramList.isEmpty(), is(false));
        PackageParameter parameter = new PackageParameter("TestParam", "TestVal", "String");
        assertThat(paramList.contains(parameter), is(true));
    }

    @Test
    void GIVEN_valid_package_recipe_without_platform_spec_WHEN_attempt_package_recipe_create_THEN_valid_package_recipe_created()
            throws IOException, URISyntaxException {
        String recipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "2.0.0");
        Package testPkg = TestHelper.getPackageObject(recipeContents);
        assertThat(testPkg.getPackageName(), is(TestHelper.MONITORING_SERVICE_PACKAGE_NAME));
        assertThat(testPkg.getVersion().getValue(), is("2.0.0"));
        assertThat(testPkg.getPublisher(), is("Me"));

        assertThat(testPkg.getLifecycle().size(), is(2));
        assertThat(testPkg.getLifecycle(), IsMapContaining.hasKey("install"));
        assertThat(testPkg.getLifecycle(), IsMapContaining.hasKey("run"));

        assertThat(testPkg.getDependencies().size(), is(0));
    }

    @Test
    void GIVEN_valid_package_recipe_WHEN_compare_to_copy_THEN_compare_returns_equal()
            throws IOException, URISyntaxException {
        // All the asserts in this can be simplified to assertEquals/NotEquals. equals/hashcode used directly
        // for coverage of both

        // Packages are same
        String monitorServiceRecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        Package monitorServicePkg = TestHelper.getPackageObject(monitorServiceRecipeContents);
        Package monitorServicePkgCopy = TestHelper.getPackageObject(monitorServiceRecipeContents);

        assertThat(monitorServicePkgCopy, is(monitorServicePkg));
        assertThat(monitorServicePkgCopy.hashCode(), is(monitorServicePkg.hashCode()));
    }

    @Test
    void GIVEN_valid_package_recipe_WHEN_compare_to_different_version_THEN_compare_returns_not_equal()
            throws IOException, URISyntaxException {
        String monitorServiceRecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        Package monitorServicePkg = TestHelper.getPackageObject(monitorServiceRecipeContents);

        // Same package different versions
        String monitorService11RecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.1.0");
        Package monitorService11Pkg = TestHelper.getPackageObject(monitorService11RecipeContents);

        assertThat(monitorService11Pkg, not(monitorServicePkg));
        assertThat(monitorService11Pkg.hashCode(), not(monitorServicePkg.hashCode()));
    }

    @Test
    void GIVEN_valid_package_recipe_WHEN_compare_to_different_recipe_THEN_compare_returns_not_equal()
            throws IOException, URISyntaxException {
        String monitorServiceRecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");
        Package monitorServicePkg = TestHelper.getPackageObject(monitorServiceRecipeContents);

        // Different packages
        String conveyorBeltRecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.CONVEYOR_BELT_PACKAGE_NAME, "1.0.0");
        Package conveyorBeltPkg = TestHelper.getPackageObject(conveyorBeltRecipeContents);

        assertThat(conveyorBeltPkg, not(monitorServicePkg));
        assertThat(conveyorBeltPkg.hashCode(), not(monitorServicePkg.hashCode()));

        // Input is not a package
        assertThat(conveyorBeltRecipeContents, not(monitorServicePkg));
    }

    @Test
    void GIVEN_invalid_recipe_version_WHEN_try_create_package_recipe_THEN_throws_exception()
            throws IOException, URISyntaxException {
        String monitorServiceRecipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.INVALID_VERSION_PACKAGE_NAME, "1.0.0");
        assertThrows(IOException.class, () -> TestHelper.getPackageObject(monitorServiceRecipeContents),
                "Expected IOException but didn't throw");
    }
}
