/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessageSubstring;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(GGExtension.class)
class KernelCommandLineTest {
    @TempDir
    Path tempRootDir;

    @BeforeEach
    void setRootDir() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
    }

    @Test
    void GIVEN_missing_parameter_to_argument_WHEN_parseArgs_THEN_throw_RuntimeException() {
        KernelCommandLine kcl = new KernelCommandLine(mock(Kernel.class), mock(NucleusPaths.class));
        RuntimeException ex = assertThrows(RuntimeException.class, () -> kcl.parseArgs("-i"));
        assertThat(ex.getMessage(), is("-i or --config requires an argument"));
    }

    @Test
    void GIVEN_invalid_command_line_argument_WHEN_parseArgs_THEN_throw_RuntimeException(ExtensionContext context) {
        KernelCommandLine kernel = new KernelCommandLine(mock(Kernel.class), mock(NucleusPaths.class));
        String exceptionSubstring = "Undefined command line argument";
        ignoreExceptionWithMessageSubstring(context, exceptionSubstring);
        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> kernel.parseArgs("-xyznonsense", "nonsense"));
        assertThat(thrown.getMessage(), containsString(exceptionSubstring));
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
    void GIVEN_kernel_WHEN_deTilde_THEN_proper_path_is_returned() throws IOException {
        Kernel mockKernel = mock(Kernel.class);
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        when(nucleusPaths.configPath()).thenReturn(tempRootDir.resolve("config"));
        when(nucleusPaths.componentStorePath()).thenReturn(tempRootDir.resolve("packages"));
        when(nucleusPaths.rootPath()).thenReturn(tempRootDir.resolve("root"));

        KernelCommandLine kcl = new KernelCommandLine(mockKernel, nucleusPaths);

        assertEquals(Paths.get(System.getProperty("user.home"), "test").toString(), kcl.deTilde("~/test"));
        assertEquals(tempRootDir.resolve("config/test").toString(), kcl.deTilde("~config/test"));
        assertEquals(tempRootDir.resolve("packages/test").toString(), kcl.deTilde("~packages/test"));
        assertEquals(tempRootDir.resolve("root/test").toString(), kcl.deTilde("~root/test"));
    }
}
