/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.extension;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;

public class InitTestSuite {
    // This test case runs once per test suite execution before the suite starts.
    // Put logic here which should run only once before all tests.
    @Test
    public void init() throws Exception {
        File surefireReportDir = new File(System.getProperty("surefireReportDir", "target/surefire-reports"));
        if (!surefireReportDir.exists()) {
            surefireReportDir.mkdirs();
        }

        File reportFile = surefireReportDir.toPath().resolve("junitReport.json").toFile();
        if (reportFile.exists()) {
            // Clear out the report file so we're not continually appending to it
            new FileWriter(reportFile, false).close();
        }
    }
}
