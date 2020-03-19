package com.aws.iot.evergreen.integrationtests;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * This class is a base IT case, meant to simplify the setup for each integration tests.
 *
 * However, individual integration test could override the setup, or just do it on their own without extending this.
 */
public abstract class AbstractBaseITCase {

    @TempDir
    static Path tempRootDir;

    @BeforeEach
    void setRootDir() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
    }
}
