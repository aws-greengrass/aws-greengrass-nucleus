/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.provision;

import androidx.test.core.app.ApplicationProvider;
import com.aws.greengrass.nucleus.test.R;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

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
        copyFromRawResource(R.raw.valid_config_yaml, config);

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);

        boolean result = provisionManager.isProvisioned();
        config.toFile().delete();

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


    /*
    @Test
    void GIVEN_system_properties_WHEN_execute_provisioning_THEN_system_properties_are_reset() {
        assertNotNull(config);
        when(config.has(anyString())).thenReturn(true);
        when(config.get(anyString()))
                .thenReturn(new TextNode("value1"))
                .thenReturn(new TextNode("value2"))
                .thenReturn(new TextNode("value3"));

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);

        // Mock scope
        //try (MockedStatic mocked = mockStatic(GreengrassSetup.class)) {
        try (MockedStatic<GreengrassSetup> mocked = mockStatic(GreengrassSetup.class)) {

            // Mocking
            mocked.when(() -> GreengrassSetup.mainForReturnKernel(any(String[].class))).thenReturn(null);

            assertThrows(NullPointerException.class, () -> provisionManager.executeProvisioning(ApplicationProvider.getApplicationContext(), config), "has_system_properties");

            verify(mocked, times(1));
            String[] expected = {"aaa", "bbb"};
            mocked.verify(() -> GreengrassSetup.mainForReturnKernel(expected), times(1));
        }

        // check system properties are cleared
        assertNull(System.getProperty(PROVISION_ACCESS_KEY_ID));
        assertNull(System.getProperty(PROVISION_SECRET_ACCESS_KEY));
        assertNull(System.getProperty(PROVISION_SESSION_TOKEN));
        //assertEquals("value1", System.getProperty(PROVISION_ACCESS_KEY_ID));
        //assertEquals("value2", System.getProperty(PROVISION_SECRET_ACCESS_KEY));
        //assertEquals("value3", System.getProperty(PROVISION_SESSION_TOKEN));
    }

    /*
    @Test
    void GIVEN_config_with_data_WHEN_prepare_arguments_THEN_correct_array_of_args() throws Exception {
        assertNotNull(config);
        when(config.has(anyString())).thenReturn(true);
        LinkedHashMap<String, String> configArgs = new LinkedHashMap<>();
        configArgs.put(PROVISION_ACCESS_KEY_ID, "value");
        configArgs.put(PROVISION_SECRET_ACCESS_KEY, "value");
        configArgs.put(PROVISION_SESSION_TOKEN, "value");
        configArgs.put("--val1", "value1");
        configArgs.put("--val2", "value2");
        configArgs.put("--val3", "value3");
        for (String key : configArgs.keySet()) {
            lenient().when(config.get(key)).thenReturn(new TextNode(configArgs.get(key)));
        }
        when(config.fieldNames()).thenReturn(configArgs.keySet().iterator());
        AtomicInteger preparedArgsCount = new AtomicInteger(2); // {"--provision", "true"}
        configArgs.forEach((k,v) -> {
            // Prepared args will contain only values starting with "-"
            if (k.startsWith("-")) {
                preparedArgsCount.addAndGet(2);
            }
        });

        ProvisionManager provisionManager = BaseProvisionManager.getInstance(tempFileDir);
        provisionManager.setConfig(config);
        String[] args = provisionManager.prepareArgsForProvisioning();

        assertEquals(preparedArgsCount.get(), args.length);
        ArrayList<String> argList = new ArrayList<>(Arrays.asList(args));
        for (String key : configArgs.keySet()) {
            if (key.startsWith("-")) {
                assertTrue(argList.contains(key));
                assertEquals(configArgs.get(key), argList.get(argList.indexOf(key) + 1));
            }
        }
        assertTrue(argList.contains("--provision"));
        assertEquals("true", argList.get(argList.indexOf("--provision") + 1));
    }

    @Test
    void GIVEN_config_with_data_WHEN_prepare_arguments_THEN_correct_count_of_called_methods() throws Exception {
        assertNotNull(config);
        when(config.has(anyString())).thenReturn(true);
        when(config.get(anyString())).thenReturn(new TextNode("value"));
        ArrayList<String> list = new ArrayList<>();
        list.add("--val1");
        list.add("--val2");
        list.add("--val3");
        when(config.fieldNames()).thenReturn(list.iterator());

        ProvisionManager provisionManagerSpy = spy(BaseProvisionManager.getInstance(tempFileDir));
        provisionManagerSpy.prepareArgsForProvisioning();

        verify(provisionManagerSpy, times(0)).isProvisioned();
        verify(provisionManagerSpy, times(0)).clearSystemProperties();
        verify(provisionManagerSpy, times(0)).clearProvision();
    }
    */

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

    private void copyFromRawResource(int resourceId, Path targetFile) throws IOException {
        try (InputStream in = ApplicationProvider.getApplicationContext().getResources().openRawResource(resourceId);
             OutputStream out = new FileOutputStream(targetFile.toFile())) {
            byte[] buffer = new byte[1024];
            while (true) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }
}
