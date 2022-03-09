/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.nucleus;

import com.aws.greengrass.android.provision.BaseProvisionManager;
import com.aws.greengrass.android.provision.ProvisionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;

import static com.aws.greengrass.android.provision.BaseProvisionManager.PROVISION_ACCESS_KEY_ID;
import static com.aws.greengrass.android.provision.BaseProvisionManager.PROVISION_SECRET_ACCESS_KEY;
import static com.aws.greengrass.android.provision.BaseProvisionManager.PROVISION_SESSION_TOKEN;
import static com.aws.greengrass.android.provision.BaseProvisionManager.THING_NAME_CHECKER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BaseProvisionManagerUnitTest {

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
    public void setup_system_properties_without_accessKeyId() {
        assertNotNull(config);
        // "when" code is added for better understanding of this test
        lenient().when(config.has(PROVISION_ACCESS_KEY_ID)).thenReturn(false);
        lenient().when(config.has(PROVISION_SECRET_ACCESS_KEY)).thenReturn(true);

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);
        provisionManager.setConfig(config);

        assertThrowsExactly(Exception.class, provisionManager::prepareArguments);
    }

    @Test
    public void setup_system_properties_without_secretAccessKey() {
        assertNotNull(config);
        lenient().when(config.has(PROVISION_ACCESS_KEY_ID)).thenReturn(true);
        lenient().when(config.has(PROVISION_SECRET_ACCESS_KEY)).thenReturn(false);
        lenient().when(config.get(anyString())).thenReturn(new TextNode("value"));

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);
        provisionManager.setConfig(config);

        assertThrowsExactly(Exception.class, provisionManager::prepareArguments);
    }

    @Test
    public void set_and_get_system_properties() {
        assertNotNull(config);
        when(config.has(anyString())).thenReturn(true);
        when(config.get(anyString()))
                .thenReturn(new TextNode("value1"))
                .thenReturn(new TextNode("value2"))
                .thenReturn(new TextNode("value3"));

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);
        provisionManager.setConfig(config);

        assertThrows(NullPointerException.class, provisionManager::prepareArguments);
        assertEquals("value1", System.getProperty(PROVISION_ACCESS_KEY_ID));
        assertEquals("value2", System.getProperty(PROVISION_SECRET_ACCESS_KEY));
        assertEquals("value3", System.getProperty(PROVISION_SESSION_TOKEN));
    }

    @Test
    public void set_and_reset_system_properties() {
        assertNotNull(config);
        when(config.has(anyString())).thenReturn(true);
        when(config.get(anyString()))
                .thenReturn(new TextNode("value1"))
                .thenReturn(new TextNode("value2"))
                .thenReturn(new TextNode("value3"));

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);
        provisionManager.setConfig(config);
        assertThrows(NullPointerException.class, provisionManager::prepareArguments);
        provisionManager.clearSystemProperties();

        assertNull(System.getProperty(PROVISION_ACCESS_KEY_ID));
        assertNull(System.getProperty(PROVISION_SECRET_ACCESS_KEY));
        assertNull(System.getProperty(PROVISION_SESSION_TOKEN));
    }

    @Test
    public void check_default_isProvisioned() {
        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);
        boolean result = provisionManager.isProvisioned();

        assertFalse(result);
    }

    @Test
    public void generate_args_and_check_values() throws Exception {
        assertNotNull(config);
        when(config.has(anyString())).thenReturn(true);
        when(config.get(anyString())).thenReturn(new TextNode("value"));
        ArrayList<String> list = new ArrayList<>();
        list.add("--val1");
        list.add("--val2");
        list.add("--val3");
        when(config.fieldNames()).thenReturn(list.iterator());

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);
        provisionManager.setConfig(config);
        String[] args = provisionManager.prepareArguments();

        assertEquals(6, args.length);
        assertEquals("--val1", args[0]);
        assertEquals("value", args[1]);
        assertEquals("--val2", args[2]);
        assertEquals("value", args[3]);
        assertEquals("--val3", args[4]);
        assertEquals("value", args[5]);
    }

    @Test
    public void checking_called_methods() throws Exception {
        assertNotNull(config);
        when(config.has(anyString())).thenReturn(true);
        when(config.get(anyString())).thenReturn(new TextNode("value"));
        ArrayList<String> list = new ArrayList<>();
        list.add("--val1");
        list.add("--val2");
        list.add("--val3");
        when(config.fieldNames()).thenReturn(list.iterator());

        ProvisionManager provisionManagerSpy = spy(BaseProvisionManager.getInstance(tempFileDir));
        provisionManagerSpy.setConfig(config);
        provisionManagerSpy.prepareArguments();

        verify(provisionManagerSpy, times(1)).isProvisioned();
        verify(provisionManagerSpy, times(0)).clearSystemProperties();
        verify(provisionManagerSpy, times(0)).clearProvision();
    }

    @Test
    public void thing_name_regexp() {
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
