/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.common;

import com.aws.iot.evergreen.packagemanager.TestHelper;
import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.packagemanager.models.PlatformSpecificRecipe;
import com.aws.iot.evergreen.packagemanager.models.RecipeDependencyProperties;
import com.aws.iot.evergreen.packagemanager.models.RecipeTemplateVersion;
import org.hamcrest.collection.IsCollectionWithSize;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

class ComponentRecipeTest {

    @Test
    void GIVEN_valid_package_recipe_WHEN_attempt_package_recipe_create_THEN_valid_package_recipe_created()
            throws IOException, URISyntaxException {
        String recipeContents =
                TestHelper.getPackageRecipeForTestPackage(TestHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0");

        System.out.println(recipeContents);
        ComponentRecipe testPkg = TestHelper.getComponentRecipeObject(recipeContents);
        assertThat(testPkg.getComponentName(), is(TestHelper.MONITORING_SERVICE_PACKAGE_NAME));
        assertThat(testPkg.getVersion().getValue(), is("1.0.0"));
        assertThat(testPkg.getPublisher(), is("Me"));
        assertThat(testPkg.getRecipeTemplateVersion(), is(RecipeTemplateVersion.JAN_25_2020));
        assertThat(testPkg.getRecipeTemplateVersion().getRecipeTemplateVersion(), is("2020-01-25"));

        assertThat(testPkg.getPlatformSpecificRecipes().size(), is(1));


        PlatformSpecificRecipe platformSpecificRecipe = testPkg.getPlatformSpecificRecipes().get(0);
        assertThat(platformSpecificRecipe.getLifecycle().size(), is(2));
        assertThat(platformSpecificRecipe.getLifecycle(), IsMapContaining.hasKey("run"));

        // TODO: Check for providers
        assertThat(platformSpecificRecipe.getArtifacts().get("all"), IsCollectionWithSize.hasSize(1));

        Map<String, RecipeDependencyProperties> dependencies = platformSpecificRecipe.getDependencies();
        assertThat(dependencies.size(), is(1));
        assertThat(dependencies, IsMapContaining.hasEntry("mac-log", new RecipeDependencyProperties("1.0")));

        Set<PackageParameter> paramList = platformSpecificRecipe.getPackageParameters();
        assertThat(paramList.isEmpty(), is(false));
        PackageParameter parameter = new PackageParameter("TestParam", "TestVal", "String");
        assertThat(paramList.contains(parameter), is(true));
    }

}