package com.aws.iot.evergreen.packagemanager.models;

import java.io.IOException;
import java.net.URISyntaxException;

import com.aws.iot.evergreen.packagemanager.TestHelper;
import com.aws.iot.evergreen.packagemanager.config.Constants;
import com.aws.iot.evergreen.packagemanager.exceptions.DefaultPlatformConfigNotFoundException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnsupportedRecipeFormatException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class PackageRecipeTests {

    @Test
    public void GIVEN_valid_package_recipe_WHEN_attempt_package_recipe_create_THEN_valid_package_recipe_created() {
        try {
            String recipeContents
                    = TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME,
                                                                "1.0.0");
            PackageRecipe testPkg = PackageRecipe.getPackageObject(recipeContents);
            assertEquals(testPkg.getPackageName(), TestHelper.MONITORING_SERVICE_PACKAGE_NAME);
            assertTrue(testPkg.getPackageVersion().isEqualTo("1.0.0"));
            assertEquals("Me", testPkg.getPublisher());
            assertEquals(testPkg.getRecipeTemplateVersion(), RecipeTemplateVersion.JAN_25_2020);
            assertEquals(testPkg.getRecipeTemplateVersion().getRecipeTemplateVersion(),
                         "2020-01-25");
            assertNotNull(testPkg.getConfig());
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }

    @Test
    public void GIVEN_empty_package_recipe_WHEN_attempt_package_recipe_create_THEN_create_throws_exception() {
        try {
            PackageRecipe testPkg = PackageRecipe.getPackageObject("");
            fail();
        } catch (UnsupportedRecipeFormatException ex) {
            ex.printStackTrace(System.out);
            assertTrue(ex.getMessage().equals("Failed to parse recipe"));
        }
    }

    @Test
    public void GIVEN_valid_package_recipe_WHEN_compare_to_copy_THEN_compare_returns_equal() {
        try {
            // All the asserts in this can be simplified to assertEquals/NotEquals. equals/hashcode used directly
            // for coverage of both

            // Packages are same
            String monitorServiceRecipeContents
                    = TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME,
                                                                "1.0.0");
            PackageRecipe monitorServicePkg = PackageRecipe.getPackageObject(monitorServiceRecipeContents);
            PackageRecipe monitorServicePkgCopy
                    = PackageRecipe.getPackageObject(monitorServiceRecipeContents);

            assertTrue(monitorServicePkg.equals(monitorServicePkgCopy));
            assertTrue(monitorServicePkg.hashCode() == monitorServicePkgCopy.hashCode());
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }

    @Test
    public void GIVEN_valid_package_recipe_WHEN_compare_to_different_version_THEN_compare_returns_not_equal()
            throws IOException, URISyntaxException, UnsupportedRecipeFormatException {
        String monitorServiceRecipeContents
                = TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME,
                                                            "1.0.0");
        PackageRecipe monitorServicePkg = PackageRecipe.getPackageObject(monitorServiceRecipeContents);

        // Same package different versions
        String monitorService11RecipeContents
                = TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME,
                                                            "1.1.0");
        PackageRecipe monitorService11Pkg = PackageRecipe.getPackageObject(monitorService11RecipeContents);

        assertFalse(monitorServicePkg.equals(monitorService11Pkg));
        assertFalse(monitorServicePkg.hashCode() == monitorService11Pkg.hashCode());
    }

    @Test
    public void GIVEN_valid_package_recipe_WHEN_compare_to_different_recipe_THEN_compare_returns_not_equal()
            throws IOException, URISyntaxException, UnsupportedRecipeFormatException {
        String monitorServiceRecipeContents
                = TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME,
                                                            "1.0.0");
        PackageRecipe monitorServicePkg = PackageRecipe.getPackageObject(monitorServiceRecipeContents);

        // Different packages
        String conveyorBeltRecipeContents
                = TestHelper.getPackageRecipeForTestPackage(TestHelper.CONVEYOR_BELT_PACKAGE_NAME,
                                                            "1.0.0");
        PackageRecipe conveyorBeltPkg = PackageRecipe.getPackageObject(conveyorBeltRecipeContents);

        assertFalse(monitorServicePkg.equals(conveyorBeltPkg));
        assertFalse(monitorServicePkg.hashCode() == conveyorBeltPkg.hashCode());

        // Input is not a package
        assertFalse(monitorServicePkg.equals(conveyorBeltRecipeContents));
    }

    @Test
    public void GIVEN_invalid_recipe_version_WHEN_try_create_package_recipe_THEN_throws_exception()
            throws IOException, URISyntaxException {
            String monitorServiceRecipeContents
                    = TestHelper.getPackageRecipeForTestPackage(TestHelper.INVALID_VERSION_PACKAGE_NAME,
                                                                "1.0.0");
            UnsupportedRecipeFormatException ex =
                    assertThrows(UnsupportedRecipeFormatException.class,
                                 () -> PackageRecipe.getPackageObject(monitorServiceRecipeContents),
                                 "Expected UnsupportedRecipeFormatException but didn't throw");
            assertEquals(ex.getMessage(), Constants.UNABLE_TO_PARSE_RECIPE_EXCEPTION_MSG);
    }

    @Test
    public void GIVEN_recipe_with_no_default_config_WHEN_try_create_package_recipe_THEN_throws_exception()
            throws IOException, URISyntaxException {
        String monitorServiceRecipeContents
                = TestHelper.getPackageRecipeForTestPackage(TestHelper.NO_DEFAULT_CONFIG_PACKAGE_NAME,
                                                            "1.0.0");
        UnsupportedRecipeFormatException ex =
                assertThrows(UnsupportedRecipeFormatException.class,
                             () -> PackageRecipe.getPackageObject(monitorServiceRecipeContents),
                             "Expected UnsupportedRecipeFormatException but didn't throw");
        assertEquals(ex.getMessage(), Constants.UNABLE_TO_PARSE_RECIPE_EXCEPTION_MSG);
    }
}
