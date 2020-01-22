package com.aws.iot.evergreen.packagemanager;

public interface RecipeProvider {

    String getPackageRecipe(String packageName, String packageVersion, String deploymentId);

}
