/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.deployment.DeploymentProcess;
import com.aws.iot.evergreen.deployment.model.DeploymentContext;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.testcommons.extensions.PerformanceReporting;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import sun.rmi.runtime.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(PerformanceReporting.class)
public class DeploymentServiceIntegrationTest {

    private static final String TEST_CUSTOMER_APP_STRING = "Hello evergreen. This is a test";
    private static final String TEST_MOSQUITTO_STRING = "Hello this is mosquitto getting started";
    private static final String TEST_TICK_TOCK_STRING = "No tick-tocking with period: 2";

    HashMap<String, Object> mockJobDocument =  new HashMap<>();
    ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    Kernel kernel;
    static Logger logger;
    static final String LogFileName = "deploymentIntegTest.log";

    @BeforeAll
    static void setupLogFile() {
        System.setProperty("log.fmt", "JSON");
        System.setProperty("log.storeName", LogFileName);
        System.setProperty("log.store", "FILE");
        System.setProperty("log.level", "INFO");
        try {
            Files.deleteIfExists(Paths.get(LogFileName));
            Files.createFile(Paths.get(LogFileName));
            logger = LogManager.getLogger(DeploymentServiceIntegrationTest.class);
        } catch (IOException e) {
            fail("Failed to create log file", e);
        }
    }

    @AfterAll
    static void cleanup() {
        try {
            Files.deleteIfExists(Paths.get(LogFileName));
        } catch (IOException e) {
            fail("Failed to delete log file", e);
        }
    }

    @BeforeEach
    public void setup() {
        try {
            String tdir = System.getProperty("user.home");
            kernel = new Kernel();
            kernel.parseArgs("-r", tdir, "-log", "stdout", "-i",
                    DeploymentServiceIntegrationTest.class.getResource("deploymentDemo.yaml").toString());
            kernel.launch();
        } catch(Exception e) {
            fail("Caught exception while setting up test");
        }
    }

    @Test
    public void GIVEN_sample_deployment_doc_WHEN_submitted_to_deployment_THEN_services_start_in_kernel()
            throws Exception {

        File fileToWatch = new File(LogFileName);
        while (!fileToWatch.exists()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        mockJobDocument = objectMapper.readValue(new File(DeploymentServiceIntegrationTest.class.getResource(
                "SampleJobDocument1.json").toURI()), HashMap.class);
        DeploymentContext deploymentContext = DeploymentContext.builder()
                .jobDocument(mockJobDocument)
                .proposedPackagesFromDeployment(new HashSet<>()).resolvedPackagesToDeploy(new HashSet<>())
                .removedTopLevelPackageNames(new HashSet<>())
                .build();
        DeploymentProcess deploymentProcess = new DeploymentProcess(deploymentContext, objectMapper, kernel,
                kernel.context.get(PackageManager.class), logger);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Future<Boolean> result = executorService.submit(deploymentProcess);
        Boolean isSuccess = result.get();
        assertTrue(isSuccess);
        String logLines = new String(Files.readAllBytes(Paths.get(LogFileName)));
        int tickTokLogStringIndex = logLines.indexOf(TEST_TICK_TOCK_STRING);
        int mosquittoLogStringIndex = logLines.indexOf(TEST_MOSQUITTO_STRING);
        int customerAppLogStringIndex = logLines.indexOf(TEST_CUSTOMER_APP_STRING);
        assertTrue(tickTokLogStringIndex != -1);
        assertTrue(mosquittoLogStringIndex != -1);
        assertTrue(customerAppLogStringIndex != -1);
        kernel.shutdown();
    }

}
