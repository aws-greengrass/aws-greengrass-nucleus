/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import androidx.test.core.app.ApplicationProvider;
import com.aws.greengrass.resources.AndroidTestResources;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static com.aws.greengrass.android.provision.BaseProvisionManager.PROVISION_ACCESS_KEY_ID;
import static com.aws.greengrass.android.provision.BaseProvisionManager.PROVISION_SECRET_ACCESS_KEY;
import static com.aws.greengrass.android.provision.BaseProvisionManager.THING_NAME_CHECKER;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith({MockitoExtension.class})
class BaseProvisionManagerTest {

    @Mock
    private JsonNode config;

    @TempDir
    Path tempDir;
    File tempFileDir;

    @BeforeEach
    public void setup() {
        try {
            tempDir = tempDir.resolve("");
        } catch (InvalidPathException e) {
            System.err.println("error creating temporary test file in " + this.getClass().getSimpleName());
        }
        tempFileDir = tempDir.toFile();
    }

    @Test
    void GIVEN_no_config_file_WHEN_is_provisioned_THEN_negative_result() {
        Paths.get(tempFileDir.toString(), "config", "config.yaml").toFile().delete();

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);

        boolean result = provisionManager.isProvisioned();
        assertFalse(result, "expected is not provisioned");
    }

    @Test
    void GIVEN_valid_config_file_WHEN_is_provisioned_THEN_positive_result() throws IOException {
        final Path config = Paths.get(tempFileDir.toString(), "greengrass", "v2", "config", "config.yaml");
        config.getParent().toFile().mkdirs();

        Path src = AndroidTestResources.getInstance().getResource("valid_provision_config.yaml", BaseProvisionManagerTest.class);
        Files.copy(src, config, StandardCopyOption.REPLACE_EXISTING);

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);

        boolean result;
        try {
            result = provisionManager.isProvisioned();
        } finally {
            config.toFile().delete();
        }

        assertTrue(result, "expected is provisioned");
    }

    @Test
    void GIVEN_execute_without_accessKeyId_WHEN_execute_provisioning_THEN_get_exception() {
        assertNotNull(config);
        // "when" code is added for better understanding of this test
        lenient().when(config.has(PROVISION_ACCESS_KEY_ID)).thenReturn(false);
        lenient().when(config.has(PROVISION_SECRET_ACCESS_KEY)).thenReturn(true);

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);

        assertThrowsExactly(Exception.class, () -> provisionManager.executeProvisioning(ApplicationProvider.getApplicationContext(), config), "execute_accessKeyId");
    }

    @Test
    void GIVEN_execute_without_secretAccessKey_WHEN_execute_provisioning_THEN_get_exception() {
        assertNotNull(config);
        lenient().when(config.has(PROVISION_ACCESS_KEY_ID)).thenReturn(true);
        lenient().when(config.has(PROVISION_SECRET_ACCESS_KEY)).thenReturn(false);
        lenient().when(config.get(anyString())).thenReturn(new TextNode("value"));

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);

        assertThrowsExactly(Exception.class, () -> provisionManager.executeProvisioning(ApplicationProvider.getApplicationContext(), config), "execute_secretAccessKey");
    }

    @Test
    void GIVEN_regexp_WHEN_checking_thing_name_THEN_correct_name_passed () {
        assertTrue("test".matches(THING_NAME_CHECKER));
        assertTrue("test_test".matches(THING_NAME_CHECKER));
        assertTrue("testTest1234".matches(THING_NAME_CHECKER));
        assertTrue("1".matches(THING_NAME_CHECKER));
        assertTrue("t".matches(THING_NAME_CHECKER));
        assertTrue(":_-".matches(THING_NAME_CHECKER));
        assertTrue("TTT".matches(THING_NAME_CHECKER));
        assertFalse("()))))".matches(THING_NAME_CHECKER));
        assertFalse(" Test".matches(THING_NAME_CHECKER));
        assertFalse("test test".matches(THING_NAME_CHECKER));
        assertFalse("test*".matches(THING_NAME_CHECKER));
        assertFalse("test^test".matches(THING_NAME_CHECKER));
        assertFalse("".matches(THING_NAME_CHECKER));
        assertFalse("     ".matches(THING_NAME_CHECKER));
    }
}
