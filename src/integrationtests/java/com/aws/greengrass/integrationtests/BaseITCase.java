package com.aws.greengrass.integrationtests;


import com.aws.greengrass.testcommons.testutilities.UniqueRootPathBeforeEach;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * This class is a base IT case to simplify the setup for integration tests.
 *
 * It creates a temp directory and sets it to "root" before each @Test.
 *
 * However, individual integration test could override the setup or just set up without extending this.
 */
@ExtendWith({GGExtension.class, UniqueRootPathBeforeEach.class})
public class BaseITCase {

    protected Path tempRootDir;

    @BeforeEach
    void setRootDir() {
        tempRootDir = Paths.get(System.getProperty("root"));
    }
}
