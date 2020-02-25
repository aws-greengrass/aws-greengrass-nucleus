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
}
