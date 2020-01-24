package com.aws.iot.evergreen.packagemanager;

public interface PackageProvider {

    String getPackageRecipe(String packageName, String packageVersion, String deploymentId);

}
