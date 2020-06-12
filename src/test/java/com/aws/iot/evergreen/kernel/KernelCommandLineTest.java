/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessageSubstring;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(EGExtension.class)
class KernelCommandLineTest {
    @TempDir
    Path tempRootDir;

    @BeforeEach
    void setRootDir() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
    }

    @Test
    void GIVEN_missing_parameter_to_argument_WHEN_parseArgs_THEN_throw_RuntimeException() {
        KernelCommandLine kcl = new KernelCommandLine(mock(Kernel.class));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> kcl.parseArgs("-i"));
        assertThat(ex.getMessage(), is("-i or --config requires an argument"));
    }

    @Test
    void GIVEN_invalid_command_line_argument_WHEN_parseArgs_THEN_throw_RuntimeException(ExtensionContext context) {
        KernelCommandLine kernel = new KernelCommandLine(mock(Kernel.class));
        String exceptionSubstring = "Undefined command line argument";
        ignoreExceptionWithMessageSubstring(context, exceptionSubstring);
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> kernel.parseArgs("-xyznonsense", "nonsense"));
        assertThat(thrown.getMessage(), containsString(exceptionSubstring));
    }

    @Test
    void GIVEN_create_path_fail_WHEN_parseArgs_THEN_throw_RuntimeException(ExtensionContext context) throws Exception {
        // Make the root path not writeable so the create path method will fail
        // Files.setPosixFilePermissions(tempRootDir, PosixFilePermissions.fromString("r-x------"));

        Kernel kernel = new Kernel();
        kernel.shutdown();
        String exceptionMessage = "Cannot create all required directories";
        ignoreExceptionWithMessage(context, exceptionMessage);
        RuntimeException thrown = assertThrows(RuntimeException.class, kernel::parseArgs);
        assertThat(thrown.getMessage(), is(exceptionMessage));
    }

    @Test
    void GIVEN_unable_to_read_config_WHEN_parseArgs_THEN_throw_RuntimeException(ExtensionContext context) throws IOException {
        Kernel mockKernel = mock(Kernel.class);
        Configuration mockConfig = mock(Configuration.class);
        when(mockKernel.getConfig()).thenReturn(mockConfig);
        when(mockConfig.read(anyString())).thenThrow(IOException.class);

        KernelCommandLine kcl = new KernelCommandLine(mockKernel);
        String exceptionMessage = "Can't read the config file test.yaml";
        ignoreExceptionWithMessage(context, exceptionMessage);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> kcl.parseArgs("-i", "test.yaml"));
        assertThat(ex.getMessage(), is(exceptionMessage));
    }

    @Test
    void GIVEN_root_argument_THEN_default_root_is_overridden() {
        Kernel kernel = new Kernel();

        Path newDir = tempRootDir.resolve("new/under/dir");
        kernel.parseArgs("-r", newDir.toString());
        kernel.shutdown();
        assertEquals(newDir.toString(), kernel.getConfig().find("system", "rootpath").getOnce());
    }

    @Test
    void GIVEN_kernel_WHEN_deTilde_THEN_proper_path_is_returned() {
        Kernel mockKernel = mock(Kernel.class);
        when(mockKernel.getClitoolPath()).thenReturn(tempRootDir.resolve("bin"));
        when(mockKernel.getConfigPath()).thenReturn(tempRootDir.resolve("config"));
        when(mockKernel.getPackageStorePath()).thenReturn(tempRootDir.resolve("packages"));
        when(mockKernel.getRootPath()).thenReturn(tempRootDir.resolve("root"));

        KernelCommandLine kcl = new KernelCommandLine(mockKernel);

        assertThat(kcl.deTilde("~/test"), containsString(System.getProperty("user.name")+ "/test"));
        assertThat(kcl.deTilde("~bin/test"), is(tempRootDir.toString()+ "/bin/test"));
        assertThat(kcl.deTilde("~config/test"), is(tempRootDir.toString()+ "/config/test"));
        assertThat(kcl.deTilde("~packages/test"), is(tempRootDir.toString()+ "/packages/test"));
        assertThat(kcl.deTilde("~root/test"), is(tempRootDir.toString()+ "/root/test"));
    }

    @Test
    void GIVEN_resource_to_install_WHEN_installCliTool_THEN_resource_is_copied_to_bin() throws IOException {
        Kernel mockKernel = mock(Kernel.class);
        when(mockKernel.getClitoolPath()).thenReturn(tempRootDir.resolve("bin"));

        KernelCommandLine kcl = new KernelCommandLine(mockKernel);
        Utils.createPaths(tempRootDir.resolve("bin"));
        File f = tempRootDir.resolve("bin/config.yaml").toFile();
        assertThat(f, not(anExistingFile()));

        kcl.installCliTool(getClass().getResource("config.yaml"));
        assertThat(f, anExistingFile());
    }

}
