package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.PackageEntry;
import com.aws.iot.evergreen.packagemanager.model.Recipe;
import java.nio.file.Path;
import java.util.Map;

public class RecipeLoader {

    private final PackageDatabaseAccessor databaseAccessor;

    public RecipeLoader(PackageDatabaseAccessor databaseAccessor) {
        this.databaseAccessor = databaseAccessor;
    }

    public Recipe loadServiceRecipes(Path rootRecipePath, Map<String, String> userParameters) {
        // Read the recipe file by file path
        String serializedRecipe = "placeholder";
        Recipe rootRecipe = constructRecipe(serializedRecipe, userParameters);

        RecipeProvider mockLocalRecipeProvider = new RecipeProvider() {
            // add implementation to find package recipe from local pre-defined folder
            @Override
            public String getPackageRecipe(String packageName, String packageVersion, String deploymentId) {
                return null;
            }
        };
        // BFS traverse the dependencies to construct dependency recipe map
        for (Recipe.Dependency dependency : rootRecipe.getDependencies()) {
            // a FIFO for BFS
            serializedRecipe = mockLocalRecipeProvider.getPackageRecipe(dependency.getPackageName(), dependency.getPackageVersion(), "deploymentId");
            Recipe recipe = constructRecipe(serializedRecipe, userParameters);
            rootRecipe.getDependencyRecipeMap().put(dependency.getPackageName()+"-"+dependency.getPackageVersion(), recipe);
        }
        return rootRecipe;
    }

    private Recipe constructRecipe(String serializedRecipe, Map<String, String> parameters) {
        // deserialize yaml string to Recipe object
        Recipe recipe = null; //placeholder

        // interpolate parameters

        checkVersionConflict(null);
        return recipe;
    }

    // Throw exception if version has conflicts
    private void checkVersionConflict(Recipe recipe) {
        PackageEntry packageEntry = databaseAccessor.get(recipe.getPackageName(), recipe.getPackageVersion());
        if (packageEntry != null) {
            //check package version, upgrade if necessary
        } else {
            databaseAccessor.create(recipe.getPackageName(), recipe.getPackageVersion());
        }

    }
}
