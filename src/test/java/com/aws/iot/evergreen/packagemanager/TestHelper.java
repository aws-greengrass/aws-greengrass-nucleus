package com.aws.iot.evergreen.packagemanager;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestHelper {
    public static final String MONITORING_SERVICE_PACKAGE_NAME = "MonitoringService";
    public static final String CONVEYOR_BELT_PACKAGE_NAME = "ConveyorBelt";
    public static final String INVALID_VERSION_PACKAGE_NAME = "InvalidRecipeVersion";
    public static final String NO_DEFAULT_CONFIG_PACKAGE_NAME = "NoDefaultPlatformConfig";

    public static String getPackageRecipeForTestPackage(String testPackageName, String testPackageVersion)
            throws IOException, URISyntaxException {
        Path rootPath = Paths.get(TestHelper.class.getResource("test_packages").toURI());
        Path path = rootPath.resolve(testPackageName + "-" + testPackageVersion).resolve("recipe.yaml");
        String recipeFmt = new String(Files.readAllBytes(path));
        return String.format(recipeFmt, rootPath.toString());
    }
}
