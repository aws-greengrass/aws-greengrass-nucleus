package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.models.Package;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());
    public static final String MONITORING_SERVICE_PACKAGE_NAME = "MonitoringService";
    public static final String CONVEYOR_BELT_PACKAGE_NAME = "ConveyorBelt";
    public static final String INVALID_VERSION_PACKAGE_NAME = "InvalidRecipeVersion";
    public static final String NO_DEFAULT_CONFIG_PACKAGE_NAME = "NoDefaultPlatformConfig";
    public static final String LOG_PACKAGE_NAME = "Log";
    public static final String COOL_DB_PACKAGE_NAME = "Cool-Database";

    public static Path getPathForLocalWorkingDirectory()
            throws URISyntaxException, IOException {
        Path path = Paths.get(TestHelper.class.getResource("plugins").toURI()).resolve("test_cache_working");
        if(Files.notExists(path)) {
            Files.createDirectories(path);
        }
        return path;
    }

    public static Path getPathForLocalTestCache()
            throws URISyntaxException, IOException {
        Path path = Paths.get(TestHelper.class.getResource("plugins").toURI()).resolve("test_cache_local");
        if(Files.notExists(path)) {
            Files.createDirectories(path);
        }
        return path;
    }

    public static Path getPathForMockRepository()
            throws URISyntaxException {
        return Paths.get(TestHelper.class.getResource("mock_artifact_source").toURI());
    }

    public static Path getPathForTestPackage(String testPackageName, String testPackageVersion)
            throws URISyntaxException {
        Path rootPath = Paths.get(TestHelper.class.getResource("test_packages").toURI());
        return rootPath.resolve(testPackageName + "-" + testPackageVersion);
    }

    static {
        OBJECT_MAPPER.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
    }

    public static Package getPackageObject(String recipe) throws IOException {
        return OBJECT_MAPPER.readValue(recipe, Package.class);
    }

    public static String getPackageRecipeForTestPackage(String testPackageName, String testPackageVersion)
            throws IOException, URISyntaxException {
        Path rootPath = Paths.get(TestHelper.class.getResource("test_packages").toURI());
        Path path = rootPath.resolve(testPackageName + "-" + testPackageVersion).resolve("recipe.yaml");
        String recipeFmt = new String(Files.readAllBytes(path));
        return String.format(recipeFmt, rootPath.toString());
    }

    public static String getPackageRecipeFromMockRepository(String testPackageName, String testPackageVersion)
            throws IOException, URISyntaxException {
        Path rootPath = Paths.get(TestHelper.class.getResource("mock_artifact_source").toURI());
        Path path = rootPath.resolve(testPackageName).resolve(testPackageVersion).resolve("recipe.yaml");
        String recipeFmt = new String(Files.readAllBytes(path));
        return String.format(recipeFmt, rootPath.toString());
    }
}
