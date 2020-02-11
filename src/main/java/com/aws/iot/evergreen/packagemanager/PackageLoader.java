package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.Package;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.Queue;
import java.util.stream.Stream;

public class PackageLoader {

    private static final String RECIPE_FILE_NAME = "recipe.yaml";
    private static final String MOCK_REPOSITORY = "mock_repository";

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

        Queue<Package> packageQueue = new LinkedList<>();
        packageQueue.offer(rootPackage);

        while (!packageQueue.isEmpty()) {
            Package pkg = packageQueue.poll();
            for (Package.Dependency dependency : pkg.getDependencies()) {
                String serializedRecipe = new MockPackageProvider().getPackageRecipe(dependency.getPackageName(),
                        dependency.getPackageVersion(), "deploymentId");
                Package dpkg = constructAndRegisterPackage(new ByteArrayInputStream(serializedRecipe.getBytes()));
                pkg.getDependencyPackageMap()
                        .put(dependency.getPackageName() + "-" + dependency.getPackageVersion(), dpkg);
                packageQueue.offer(dpkg);
            }
        }

        return rootPackage;
    }

    public Package loadPackage(String packageName, String packageVersion) {
         PackageProvider packageProvider = new MockPackageProvider();
         Package targetPackage = constructAndRegisterPackage(new ByteArrayInputStream(packageProvider.getPackageRecipe(packageName, packageVersion,
                 "deploymentId").getBytes()));

        Queue<Package> packageQueue = new LinkedList<>();
        packageQueue.offer(targetPackage);

        while (!packageQueue.isEmpty()) {
            Package pkg = packageQueue.poll();
            for (Package.Dependency dependency : pkg.getDependencies()) {
                String serializedRecipe = packageProvider.getPackageRecipe(dependency.getPackageName(),
                        dependency.getPackageVersion(), "deploymentId");
                Package dpkg = constructAndRegisterPackage(new ByteArrayInputStream(serializedRecipe.getBytes()));
                pkg.getDependencyPackageMap()
                        .put(dependency.getPackageName() + "-" + dependency.getPackageVersion(), dpkg);
                packageQueue.offer(dpkg);
            }
        }

        return targetPackage;
    }

    private Package constructAndRegisterPackage(InputStream recipeStream) {
        Package pkg = null;
        try {
            pkg = OBJECT_MAPPER.readValue(recipeStream, Package.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse recipe", e);
        }

        databaseAccessor.createPackageIfNotExist(pkg.getPackageName(), pkg.getPackageVersion());
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

    public static class MockPackageProvider implements PackageProvider {

        @Override
        public String getPackageRecipe(String packageName, String packageVersion, String deploymentId) {
            Path recipePath = Paths.get(System.getProperty("user.dir"))
                    .resolve(MOCK_REPOSITORY)
                    .resolve(packageName + "-" + packageVersion)
                    .resolve(RECIPE_FILE_NAME);

            return readFile(recipePath);
        }

        private String readFile(Path filePath) {
            StringBuilder stringBuilder = new StringBuilder();

            try (Stream<String> stream = Files.lines(filePath)) {
                stream.forEach(s -> stringBuilder.append(s)
                        .append("\n"));
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file " + filePath, e);
            }

            return stringBuilder.toString();
        }
    }
}
