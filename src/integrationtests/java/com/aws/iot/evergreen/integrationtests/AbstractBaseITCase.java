package com.aws.iot.evergreen.integrationtests;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * This class is a base IT case to simplify the setup for each integration tests.
 *
 * It creates a temp directory and set to root property before each test.
 *
 * However, individual integration test could override the setup, or just do it on their own without extending this.
 */
public abstract class AbstractBaseITCase {

    @TempDir
    Path tempRootDir;

    @BeforeEach
    void setRootDir() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
    }
}
