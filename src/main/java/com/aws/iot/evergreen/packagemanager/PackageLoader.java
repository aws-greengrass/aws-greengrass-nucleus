package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.Package;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public class PackageLoader {

    private static final String RECIPE_FILE_NAME = "recipe.yaml";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    private final PackageDatabaseAccessor databaseAccessor;

    public PackageLoader(PackageDatabaseAccessor databaseAccessor) {
        this.databaseAccessor = databaseAccessor;
    }

    public Package loadPackage(Path packagePath) {
        // Read the recipe file by file path
        Path recipePath = packagePath.resolve(RECIPE_FILE_NAME);
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(recipePath.toString());
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Failed to find recipe.yaml file");
        }
        Package rootPackage = constructAndRegisterPackage(inputStream);

        RecipeProvider mockLocalRecipeProvider = new RecipeProvider() {
            // add implementation to find package recipe from local pre-defined folder
            @Override
            public String getPackageRecipe(String packageName, String packageVersion, String deploymentId) {
                return null;
            }
        };
        // BFS traverse the dependencies to construct dependency recipe map
        for (Package.Dependency dependency : rootPackage.getDependencies()) {
            // a FIFO for BFS
            String serializedRecipe = mockLocalRecipeProvider.getPackageRecipe(dependency.getPackageName(), dependency.getPackageVersion(), "deploymentId");
            Package aPackage = constructAndRegisterPackage(new ByteArrayInputStream(serializedRecipe.getBytes()));
            rootPackage.getDependencyRecipeMap().put(dependency.getPackageName()+"-"+dependency.getPackageVersion(), aPackage);
        }
        return rootPackage;
    }

    private Package constructAndRegisterPackage(InputStream recipeStream) {
        Package pkg = null;
        try {
            pkg = OBJECT_MAPPER.readValue(recipeStream, Package.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse recipe", e);
        }

        databaseAccessor.createIfNotExist(pkg.getPackageName(), pkg.getPackageVersion());
        return pkg;
    }

    // Throw exception if version has conflicts
//    private void checkVersionConflict(Package aPackage) {
//        PackageEntry packageEntry = databaseAccessor.get(aPackage.getPackageName(), aPackage.getPackageVersion());
//        if (packageEntry != null) {
//            //check package version, upgrade if necessary
//        } else {
//            databaseAccessor.create(aPackage.getPackageName(), aPackage.getPackageVersion());
//        }
//
//    }
}
