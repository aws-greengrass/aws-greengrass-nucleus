/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager;

import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogFormat;
import com.aws.greengrass.logging.impl.config.LogStore;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.UUID;

import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.io.FileMatchers.aFileNamed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class FileLoggerTest extends BaseITCase {
    private Kernel kernel;

    @BeforeAll
    static void beforeAll() {
        LogManager.getRootLogConfiguration().setStore(LogStore.FILE);
        LogManager.getRootLogConfiguration().setFormat(LogFormat.TEXT);
    }

    @AfterAll
    static void cleanup() {
        LogManager.getRootLogConfiguration().setStore(LogStore.CONSOLE);
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_root_path_given_as_system_param_WHEN_kernel_launches_THEN_logs_written_to_correct_directory()
            throws Exception {
        // launch kernel without config arg
        kernel = new Kernel().parseArgs().launch();

        GreengrassService mainService = kernel.locate("main");
        assertNotNull(mainService);

        // verify that log file exists are the correct location.
        File logFile = tempRootDir.resolve("logs").resolve("greengrass.log").toFile();
        MatcherAssert.assertThat(logFile, aFileNamed(equalToIgnoringCase("greengrass.log")));
        assertTrue(logFile.length() > 0);
    }

    @Test
    void GIVEN_root_path_given_as_kernel_param_WHEN_kernel_launches_THEN_logs_written_to_correct_directory()
            throws Exception {
        // launch kernel without config arg
        String randomDirectory = UUID.randomUUID().toString();
        kernel = new Kernel().parseArgs("-r", tempRootDir.resolve(randomDirectory).toAbsolutePath().toString())
                .launch();

        GreengrassService mainService = kernel.locate("main");
        assertNotNull(mainService);

        // verify that log file exists are the correct location.
        File logFile = tempRootDir.resolve(randomDirectory).resolve("logs").resolve("greengrass.log").toFile();
        File oldLogFile = tempRootDir.resolve("logs").resolve("greengrass.log").toFile();
        MatcherAssert.assertThat(logFile, aFileNamed(equalToIgnoringCase("greengrass.log")));
        assertEquals(0, oldLogFile.length());
        assertTrue(logFile.length() > 0);
    }
}
