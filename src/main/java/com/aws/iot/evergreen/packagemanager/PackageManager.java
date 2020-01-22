package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.model.Recipe;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PackageManager {

    // For POC, use a concurrent map acts as service registry.
    private final ConcurrentHashMap<String, Recipe> serviceRegistryMap = new ConcurrentHashMap<>();

    private final RecipeLoader recipeLoader;

    private final ArtifactCache artifactCache;

    private final SoftwareInstaller softwareInstaller;

    // Inject kernel
    private Kernel kernel;

    public PackageManager(RecipeLoader recipeLoader, ArtifactCache artifactCache, SoftwareInstaller softwareInstaller) {
        this.recipeLoader = recipeLoader;
        this.artifactCache  = artifactCache;
        this.softwareInstaller = softwareInstaller;
    }

    public void loadPackage(String packageFolder, Map<String, String> userParameters) {
        //look for recipe.yaml in the package folder, construct the file path
        Recipe rootRecipe = recipeLoader.loadServiceRecipes(Paths.get("placeholder"), userParameters);
        // cache artifact
        artifactCache.cacheArtifact(rootRecipe);

        //root recipe should contain service name
        serviceRegistryMap.put(rootRecipe.getServiceName(), rootRecipe);

        installService(rootRecipe.getServiceName());
    }

    public void installService(String serviceName) {
        Recipe rootRecipe = serviceRegistryMap.get(serviceName);
        // install software
        softwareInstaller.copyInstall(rootRecipe);

        //extract lifecycle runtime config from rootRecipe
        //invoke kernel to reload the config
    }

}
