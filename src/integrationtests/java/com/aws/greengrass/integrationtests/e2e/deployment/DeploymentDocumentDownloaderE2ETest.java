/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.deployment;

import com.aws.greengrass.deployment.DeploymentDocumentDownloader;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This class tests the downloading function for large deployment document during development and will eventually be
 * covered by UATs. Only works for BETA now.
 * <p>
 * It is not a full end-to-end customer use case test. It is placed in E2E test because it requires AWS cred and
 * provisioning to reach GG cloud data plane.
 */
@ExtendWith(GGExtension.class)
@Tag("E2E")
class DeploymentDocumentDownloaderE2ETest extends BaseE2ETestCase {

    private static DeploymentDocumentDownloader downloader;

    protected DeploymentDocumentDownloaderE2ETest() throws Exception {
        super();
    }

    @BeforeEach
    void setupKernel() throws Exception {
        // The integration test will pick up credentials from the default provider chain
        // In automated testing, the device environment should ideally have credentials for all tests
        // For dev work, this requires you to have a working set of AWS Credentials on your dev box and/or your IDE
        // environment
        initKernel();
        kernel.launch();

        // get required instances from context
        downloader = kernel.getContext().get(DeploymentDocumentDownloader.class);
    }

    @AfterEach
    void tearDown() {
        kernel.shutdown();
        cleanup();
    }

    @Test
    void GIVEN_stub_deployment_doc_in_cloud_WHEN_download_THEN_document_gets_downloaded() throws Exception {
        DeploymentDocument deploymentDocument = downloader.download("stub-deployment-id");
        assertTrue(deploymentDocument.getRequiredCapabilities().contains("LARGE_CONFIGURATION"));
    }
}
