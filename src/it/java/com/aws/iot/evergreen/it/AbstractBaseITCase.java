package com.aws.iot.evergreen.it;


import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

public abstract class AbstractBaseITCase {

    @TempDir
    protected static Path tempRootDir;

    @BeforeAll
    static void setSystemProperties() {
        System.setProperty("log.fmt", "TEXT");
        System.setProperty("log.store", "CONSOLE");
        System.setProperty("log.level", "INFO");
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
    }
}
