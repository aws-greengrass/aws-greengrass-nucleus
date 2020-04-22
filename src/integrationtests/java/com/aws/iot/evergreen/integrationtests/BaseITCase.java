package com.aws.iot.evergreen.integrationtests;


import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * This class is a base IT case to simplify the setup for integration tests.
 *
 * It creates a temp directory and sets it to "root" before each @Test.
 *
 * However, individual integration test could override the setup or just set up without extending this.
 */
@ExtendWith(EGExtension.class)
public class BaseITCase {

    @TempDir
    protected Path tempRootDir;

    @BeforeEach
    void setRootDir() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
    }
}
