/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.easysetup;

import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.platforms.Platform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.stream.Stream;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class GreengrassSetupTest {
    @Mock
    private DeviceProvisioningHelper deviceProvisioningHelper;
    @Mock
    private Kernel kernel;

    @Mock
    private DeviceProvisioningHelper.ThingInfo thingInfo;

    private GreengrassSetup greengrassSetup;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Platform platform;

    @Test
    void GIVEN_setup_script_WHEN_script_is_used_THEN_setup_actions_are_performed() throws Exception {
        when(deviceProvisioningHelper.createThing(any(), any())).thenReturn(thingInfo);
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform,
                        "--config", "mock_config_path",
                        "--root", "mock_root", "--thing-name", "mock_thing_name", "--thing-group-name",
                        "mock_thing_group_name", "--tes-role-name", "mock_tes_role_name", "--tes-role-alias-name",
                        "mock_tes_role_alias_name", "--provision", "y", "--aws-region", "us-east-1");
        greengrassSetup.parseArgs();
        greengrassSetup.setDeviceProvisioningHelper(deviceProvisioningHelper);
        greengrassSetup.provision(kernel);
        verify(deviceProvisioningHelper, times(1)).createThing(any(), any());
        verify(deviceProvisioningHelper, times(1)).addThingToGroup(any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).updateKernelConfigWithIotConfiguration(any(), any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).setupIoTRoleForTes(any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).createAndAttachRolePolicy(any());
    }

    @Test
    void GIVEN_no_default_user_WHEN_script_is_used_THEN_no_user_created() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, "--config",
                        "mock_config_path", "--root", "mock_root");

        GreengrassSetup greengrassSetupSpy = spy(greengrassSetup);
        doReturn(kernel).when(greengrassSetupSpy).getKernel();
        doReturn(kernel).when(kernel).parseArgs(any());
        greengrassSetupSpy.parseArgs();
        greengrassSetupSpy.performSetup();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(kernel).parseArgs(args.capture());

        verify(platform, times(0)).createUser(eq("ggc_user"));
        verify(platform, times(0)).createGroup(eq("ggc_group"));

        assertThat(args.getAllValues(), not(hasItems("--component-default-user", "ggc_user:ggc_group")));
    }

    @Test
    void GIVEN_default_user_WHEN_script_is_used_THEN_default_user_created() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, "--config",
                        "mock_config_path", "--root", "mock_root", "--component-default-user", "ggc_user:ggc_group");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        when(platform.lookupUserByName(any())).thenThrow(IOException.class);
        when(platform.lookupGroupByName(any())).thenThrow(IOException.class);
        GreengrassSetup greengrassSetupSpy = spy(greengrassSetup);
        doReturn(kernel).when(greengrassSetupSpy).getKernel();
        doReturn(kernel).when(kernel).parseArgs(any());
        doReturn(kernel).when(kernel).launch();
        greengrassSetupSpy.parseArgs();
        greengrassSetupSpy.performSetup();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(kernel).parseArgs(args.capture());
        assertThat(args.getAllValues(), hasItems("--component-default-user", "ggc_user:ggc_group"));

        verify(platform).createUser(eq("ggc_user"));
        verify(platform).createGroup(eq("ggc_group"));
    }

    @Test
    void GIVEN_existing_default_user_WHEN_script_is_used_THEN_default_user_not_created() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, "--config",
                        "mock_config_path", "--root", "mock_root", "--component-default-user", "ggc_user:ggc_group");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        GreengrassSetup greengrassSetupSpy = spy(greengrassSetup);
        doReturn(kernel).when(greengrassSetupSpy).getKernel();
        doReturn(kernel).when(kernel).parseArgs(any());
        doReturn(kernel).when(kernel).launch();
        greengrassSetupSpy.parseArgs();
        greengrassSetupSpy.performSetup();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(kernel).parseArgs(args.capture());
        assertThat(args.getAllValues(), hasItems("--component-default-user", "ggc_user:ggc_group"));

        verify(platform, times(0)).createUser(eq("ggc_user"));
        verify(platform, times(0)).createGroup(eq("ggc_group"));
    }

    @Test
    void GIVEN_existing_non_default_user_WHEN_script_is_used_THEN_user_not_created_and_passed_to_kernel() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, "--config",
                        "mock_config_path", "--root", "mock_root", "--component-default-user", "foo:bar");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        GreengrassSetup greengrassSetupSpy = spy(greengrassSetup);
        doReturn(kernel).when(greengrassSetupSpy).getKernel();
        doReturn(kernel).when(kernel).parseArgs(any());
        doReturn(kernel).when(kernel).launch();
        greengrassSetupSpy.parseArgs();
        greengrassSetupSpy.performSetup();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(kernel).parseArgs(args.capture());
        assertThat(args.getAllValues(), hasItems("--component-default-user", "foo:bar"));

        verify(platform, times(0)).createUser(any());
        verify(platform, times(0)).createGroup(any());
    }

    @Test
    void GIVEN_existing_non_default_user_no_group_WHEN_script_is_used_THEN_user_passed_to_kernel() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, "--config",
                        "mock_config_path", "--root", "mock_root", "--component-default-user", "foo");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        GreengrassSetup greengrassSetupSpy = spy(greengrassSetup);
        doReturn(kernel).when(greengrassSetupSpy).getKernel();
        doReturn(kernel).when(kernel).parseArgs(any());
        doReturn(kernel).when(kernel).launch();
        greengrassSetupSpy.parseArgs();
        greengrassSetupSpy.performSetup();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(kernel).parseArgs(args.capture());
        assertThat(args.getAllValues(), hasItems("--component-default-user", "foo"));

        verify(platform, times(0)).createUser(any());
        verify(platform, times(0)).createGroup(any());
    }

    @ParameterizedTest
    @MethodSource("invalidUsers")
    void GIVEN_invalid_user_WHEN_script_is_used_THEN_error(String user) throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, "--config",
                        "mock_config_path", "--root", "mock_root", "--component-default-user", user);

        GreengrassSetup greengrassSetupSpy = spy(greengrassSetup);

        Exception e = assertThrows(RuntimeException.class, () -> {
            greengrassSetupSpy.parseArgs();
            when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
            greengrassSetupSpy.performSetup();
        });
        assertThat(e.getMessage(), containsString("No user specified"));
    }

    static Stream<Arguments> invalidUsers() {
        return Stream.of(
                Arguments.arguments(":foo"),
                Arguments.arguments(":"),
                Arguments.arguments("")
        );
    }

    @Test
    void GIVEN_setup_script_WHEN_no_tes_role_args_not_provided_THEN_tes_setup_with_efault() throws Exception {
        when(deviceProvisioningHelper.createThing(any(), any())).thenReturn(thingInfo);
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform,
                        "--config", "mock_config_path", "--root", "mock_root", "--thing-name", "mock_thing_name",
                        "--provision", "y", "--aws-region", "us-east-1");
        greengrassSetup.parseArgs();
        greengrassSetup.setDeviceProvisioningHelper(deviceProvisioningHelper);
        greengrassSetup.provision(kernel);
        verify(deviceProvisioningHelper, times(1)).createThing(any(), any());
        verify(deviceProvisioningHelper, times(1)).updateKernelConfigWithIotConfiguration(any(), any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).setupIoTRoleForTes(any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).createAndAttachRolePolicy(any());
    }

    @Test
    void GIVEN_setup_script_WHEN_script_is_used_with_short_arg_notations_THEN_setup_actions_are_performed()
            throws Exception {
        when(deviceProvisioningHelper.createThing(any(), any())).thenReturn(thingInfo);
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, "-i",
                        "mock_config_path", "-r", "mock_root", "-tn", "mock_thing_name", "-tgn",
                        "mock_thing_group_name", "-trn", "mock_tes_role_name", "-tra", "mock_tes_role_alias_name",
                        "-p", "y", "-ar", "us-east-1");
        greengrassSetup.parseArgs();
        greengrassSetup.setDeviceProvisioningHelper(deviceProvisioningHelper);
        greengrassSetup.provision(kernel);
        verify(deviceProvisioningHelper, times(1)).createThing(any(), any());
        verify(deviceProvisioningHelper, times(1)).addThingToGroup(any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).updateKernelConfigWithIotConfiguration(any(), any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).setupIoTRoleForTes(any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).createAndAttachRolePolicy(any());
    }

    @Test
    void GIVEN_setup_script_WHEN_script_is_used_with_unknown_args_THEN_script_fails(ExtensionContext context) {
        ignoreExceptionUltimateCauseWithMessage(context, "Undefined command line argument: -x");
        assertThrows(RuntimeException.class,
                () -> new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform,
                        "-i", "mock_config_path", "-r", "mock_root", "-tn", "mock_thing_name", "-x",
                        "mock_wrong_arg_value", "-trn", "mock_tes_role_name").parseArgs());
    }

    @Test
    void GIVEN_setup_script_WHEN_dry_run_THEN_kernel_not_launched() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, "--config",
                        "mock_config_path", "--root", "mock_root", "--start", "false");

        GreengrassSetup greengrassSetupSpy = spy(greengrassSetup);
        doReturn(kernel).when(greengrassSetupSpy).getKernel();
        doReturn(kernel).when(kernel).parseArgs(any());
        greengrassSetupSpy.parseArgs();
        greengrassSetupSpy.performSetup();
        verify(kernel, times(0)).launch();
    }
}
