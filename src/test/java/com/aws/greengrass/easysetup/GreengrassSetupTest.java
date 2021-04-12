/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.easysetup;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.platforms.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class GreengrassSetupTest {
    @Mock
    private DeviceProvisioningHelper deviceProvisioningHelper;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Kernel kernel;
    @Mock
    private Context context;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private Topic runWithDefaultPosixUserTopic;
    @Mock
    private DeviceProvisioningHelper.ThingInfo thingInfo;

    private GreengrassSetup greengrassSetup;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Platform platform;

    static Stream<Arguments> invalidUsers() {
        return Stream.of(Arguments.arguments(":foo"), Arguments.arguments(":"));
    }

    @BeforeEach
    void setup() {
        lenient().doReturn(context).when(kernel).getContext();
        lenient().doReturn(deviceConfiguration).when(context).get(DeviceConfiguration.class);
        lenient().doReturn(runWithDefaultPosixUserTopic).when(deviceConfiguration).getRunWithDefaultPosixUser();
        lenient().doReturn("").when(runWithDefaultPosixUserTopic).getOnce();
        lenient().doReturn(runWithDefaultPosixUserTopic).when(runWithDefaultPosixUserTopic).withValue(anyString());
    }

    @AfterEach
    void cleanup() throws IOException {
        context.close();
    }

    @Test
    void GIVEN_setup_script_WHEN_script_is_used_THEN_setup_actions_are_performed() throws Exception {
        when(deviceProvisioningHelper.createThing(any(), any())).thenReturn(thingInfo);
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--thing-name", "mock_thing_name",
                        "--thing-group-name", "mock_thing_group_name", "--tes-role-name", "mock_tes_role_name",
                        "--tes-role-alias-name", "mock_tes_role_alias_name", "--provision", "y", "--aws-region",
                        "us-east-1", "-ss", "false");
        greengrassSetup.parseArgs();
        greengrassSetup.setDeviceProvisioningHelper(deviceProvisioningHelper);
        greengrassSetup.provision(kernel);
        verify(deviceProvisioningHelper, times(1)).createThing(any(), any());
        verify(deviceProvisioningHelper, times(1)).addThingToGroup(any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).updateKernelConfigWithIotConfiguration(any(), any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).setupIoTRoleForTes(any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).createAndAttachRolePolicy(any(), any());
    }

    @Test
    void GIVEN_no_default_user_WHEN_script_is_used_THEN_default_user_created_and_added_to_config() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--aws-region", "us-east-1", "-ss", "false");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        when(platform.userExists(any())).thenReturn(false);
        when(platform.lookupGroupByName(any())).thenThrow(IOException.class);
        doReturn(kernel).when(kernel).parseArgs(any());
        greengrassSetup.parseArgs();
        greengrassSetup.performSetup();
        verify(platform).createUser(eq("ggc_user"));
        verify(platform).createGroup(eq("ggc_group"));
        verify(deviceConfiguration).getRunWithDefaultPosixUser();
        ArgumentCaptor<String> deviceConfigArg = ArgumentCaptor.forClass(String.class);
        verify(runWithDefaultPosixUserTopic).withValue(deviceConfigArg.capture());
        assertThat(deviceConfigArg.getAllValues(), hasItems("ggc_user:ggc_group"));
    }

    @Test
    void GIVEN_ggc_user_as_arg_WHEN_script_is_used_THEN_default_user_created_and_added_to_config() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--aws-region", "us-east-1", "-ss", "false", "-u",
                        "ggc_user");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        when(platform.userExists(any())).thenReturn(false);
        when(platform.lookupGroupByName(any())).thenThrow(IOException.class);
        doReturn(kernel).when(kernel).parseArgs(any());
        doReturn("ggc_user").when(runWithDefaultPosixUserTopic).getOnce();
        greengrassSetup.parseArgs();
        greengrassSetup.performSetup();
        verify(platform).createUser(eq("ggc_user"));
        verify(platform, times(0)).createGroup(eq("ggc_group"));
        verify(deviceConfiguration).getRunWithDefaultPosixUser();
        // -u arg was present, so it would have been automatically added to config in kernel.parseArgs
        // meaning the setup shouldn't add it again.
        verify(runWithDefaultPosixUserTopic, times(0)).withValue(anyString());
        verify(kernel).launch();
    }

    @Test
    void GIVEN_no_default_user_arg_but_user_present_in_config_WHEN_script_is_used_THEN_user_from_config_used()
            throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--aws-region", "us-east-1", "-ss", "false");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        doReturn(kernel).when(kernel).parseArgs(any());
        doReturn("existingUser").when(runWithDefaultPosixUserTopic).getOnce();
        greengrassSetup.parseArgs();
        greengrassSetup.performSetup();
        verify(deviceConfiguration).getRunWithDefaultPosixUser();
        verify(runWithDefaultPosixUserTopic).getOnce();
        verify(runWithDefaultPosixUserTopic, times(0)).withValue(anyString());
        verify(platform, times(0)).createUser(eq("ggc_user"));
        verify(platform, times(0)).createGroup(eq("ggc_group"));
        verify(platform, times(0)).addUserToGroup(eq("ggc_user"), eq("ggc_group"));
    }

    @Test
    void GIVEN_default_user_arg_and_user_present_in_config_WHEN_script_is_used_THEN_default_user_arg_used()
            throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--aws-region", "us-east-1", "-ss", "false",
                        "--component-default-user", "uid:gid");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        when(platform.userExists(any())).thenReturn(true);
        doReturn(kernel).when(kernel).parseArgs(any());
        greengrassSetup.parseArgs();
        greengrassSetup.performSetup();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(kernel).parseArgs(args.capture());
        assertThat(args.getAllValues(), hasItems("--component-default-user", "uid:gid"));
        verify(deviceConfiguration).getRunWithDefaultPosixUser();
        verify(runWithDefaultPosixUserTopic).getOnce();
        verify(runWithDefaultPosixUserTopic, timeout(0)).withValue(anyString());
        verify(platform, times(0)).createUser(eq("ggc_user"));
        verify(platform, times(0)).createGroup(eq("ggc_group"));
        verify(platform, times(0)).addUserToGroup(eq("ggc_user"), eq("ggc_group"));
    }

    @Test
    void GIVEN_default_user_WHEN_script_is_used_THEN_default_user_created() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--component-default-user", "ggc_user:ggc_group",
                        "--aws-region", "us-east-1", "-ss", "false");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        when(platform.userExists(any())).thenReturn(false);
        when(platform.lookupGroupByName(any())).thenThrow(IOException.class);
        doReturn(kernel).when(kernel).parseArgs(any());
        doReturn(kernel).when(kernel).launch();
        greengrassSetup.parseArgs();
        greengrassSetup.performSetup();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(kernel).parseArgs(args.capture());
        assertThat(args.getAllValues(), hasItems("--component-default-user", "ggc_user:ggc_group"));

        verify(platform).createUser(eq("ggc_user"));
        verify(platform).createGroup(eq("ggc_group"));
        verify(platform).addUserToGroup(eq("ggc_user"), eq("ggc_group"));
    }

    @Test
    void GIVEN_existing_default_user_WHEN_script_is_used_THEN_default_user_not_created() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--component-default-user", "ggc_user:ggc_group",
                        "--aws-region", "us-east-1", "-ss", "false");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        when(platform.userExists(any())).thenReturn(true);
        doReturn(kernel).when(kernel).parseArgs(any());
        doReturn(kernel).when(kernel).launch();
        greengrassSetup.parseArgs();
        greengrassSetup.performSetup();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(kernel).parseArgs(args.capture());
        assertThat(args.getAllValues(), hasItems("--component-default-user", "ggc_user:ggc_group"));

        verify(platform, times(0)).createUser(eq("ggc_user"));
        verify(platform, times(0)).createGroup(eq("ggc_group"));
        verify(platform, times(0)).addUserToGroup(eq("ggc_user"), eq("ggc_group"));
    }

    @Test
    void GIVEN_existing_non_default_user_WHEN_script_is_used_THEN_user_not_created_and_passed_to_kernel()
            throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--component-default-user", "foo:bar",
                        "--aws-region", "us-east-1", "-ss", "false");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        when(platform.userExists(any())).thenReturn(true);
        doReturn(kernel).when(kernel).parseArgs(any());
        doReturn(kernel).when(kernel).launch();
        greengrassSetup.parseArgs();
        greengrassSetup.performSetup();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(kernel).parseArgs(args.capture());
        assertThat(args.getAllValues(), hasItems("--component-default-user", "foo:bar"));

        verify(platform, times(0)).createUser(any());
        verify(platform, times(0)).createGroup(any());
        verify(platform, times(0)).addUserToGroup(any(), any());
    }

    @Test
    void GIVEN_existing_non_default_user_no_group_WHEN_script_is_used_THEN_user_passed_to_kernel() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--component-default-user", "foo", "--aws-region",
                        "us-east-1", "-ss", "false");

        when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
        when(platform.userExists(any())).thenReturn(true);
        doReturn(kernel).when(kernel).parseArgs(any());
        doReturn(kernel).when(kernel).launch();
        greengrassSetup.parseArgs();
        greengrassSetup.performSetup();
        ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
        verify(kernel).parseArgs(args.capture());
        assertThat(args.getAllValues(), hasItems("--component-default-user", "foo"));

        verify(platform, times(0)).createUser(any());
        verify(platform, times(0)).createGroup(any());
        verify(platform, times(0)).addUserToGroup(any(), any());
    }

    @ParameterizedTest
    @MethodSource("invalidUsers")
    void GIVEN_invalid_user_WHEN_script_is_used_THEN_error(String user, ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, IOException.class);
        Kernel realKernel = new Kernel();
        greengrassSetup = new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, realKernel,
                "--component-default-user", user, "--aws-region", "us-east-1", "-ss", "false");
        Exception e = assertThrows(RuntimeException.class, () -> {
            greengrassSetup.parseArgs();
            greengrassSetup.performSetup();
        });
        realKernel.shutdown();
        assertThat(e.getMessage(), containsString(
                String.format("Error while looking up primary group for %s. No " + "group specified for the user",
                        user)));
    }

    @Test
    void GIVEN_blank_user_WHEN_script_is_used_THEN_error() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--component-default-user", "", "--aws-region",
                        "us-east-1", "-ss", "false");

        Exception e = assertThrows(RuntimeException.class, () -> {
            greengrassSetup.parseArgs();
            when(platform.lookupCurrentUser().isSuperUser()).thenReturn(true);
            greengrassSetup.performSetup();
        });
        assertThat(e.getMessage(), containsString("No user specified"));
    }

    @Test
    void GIVEN_setup_script_WHEN_no_region_provided_THEN_fail() {
        GreengrassSetup greengrassSetup = new GreengrassSetup(System.out, System.err, deviceProvisioningHelper,
                platform, kernel, "-i",
                "mock_config_path", "-r", "mock_root", "-tn", "mock_thing_name", "-trn", "mock_tes_role_name",
                "-ss", "false");
        greengrassSetup.parseArgs();
        Exception e = assertThrows(RuntimeException.class, greengrassSetup::performSetup);
        assertThat(e.getMessage(), containsString("aws region not provided"));
    }

    @Test
    void GIVEN_setup_script_WHEN_bad_region_provided_THEN_fail() {
        GreengrassSetup greengrassSetup = new GreengrassSetup(System.out, System.err, deviceProvisioningHelper,
                platform, kernel, "-i",
                "mock_config_path", "-r", "mock_root", "-tn", "mock_thing_name", "-trn", "mock_tes_role_name",
                "-ss", "false", "--aws-region", "nowhere");
        greengrassSetup.parseArgs();
        Exception e = assertThrows(RuntimeException.class, greengrassSetup::performSetup);
        assertThat(e.getMessage(), containsString("is invalid AWS region"));
    }

    @Test
    void GIVEN_setup_script_WHEN_no_region_arg_provided_but_region_in_config_THEN_proceed(ExtensionContext context) throws Exception {
        GreengrassSetup greengrassSetup = new GreengrassSetup(System.out, System.err, deviceProvisioningHelper,
                platform, kernel, "-i",
                "mock_config_path", "-r", "mock_root", "-tn", "mock_thing_name", "-trn", "mock_tes_role_name",
                "-ss", "false");
        Topic regionTopic = Topic.of(this.context, DeviceConfiguration.DEVICE_PARAM_AWS_REGION, "us-east-1");
        lenient().doReturn(regionTopic).when(deviceConfiguration).getAWSRegion();
        greengrassSetup.parseArgs();
        greengrassSetup.performSetup();
        verify(kernel).launch();
    }

    @Test
    void GIVEN_setup_script_WHEN_no_tes_role_args_provided_THEN_tes_setup_with_default() throws Exception {
        when(deviceProvisioningHelper.createThing(any(), any())).thenReturn(thingInfo);
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--thing-name", "mock_thing_name", "--provision",
                        "y", "--aws-region", "us-east-1", "-ss", "false");
        greengrassSetup.parseArgs();
        greengrassSetup.setDeviceProvisioningHelper(deviceProvisioningHelper);
        greengrassSetup.provision(kernel);
        verify(deviceProvisioningHelper, times(1)).createThing(any(), any());
        verify(deviceProvisioningHelper, times(1)).updateKernelConfigWithIotConfiguration(any(), any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).setupIoTRoleForTes(any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).createAndAttachRolePolicy(any(), any());
    }

    @Test
    void GIVEN_setup_script_WHEN_script_is_used_with_short_arg_notations_THEN_setup_actions_are_performed()
            throws Exception {
        when(deviceProvisioningHelper.createThing(any(), any())).thenReturn(thingInfo);
        greengrassSetup = new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "-i",
                "mock_config_path", "-r", "mock_root", "-tn", "mock_thing_name", "-tgn", "mock_thing_group_name",
                "-trn", "mock_tes_role_name", "-tra", "mock_tes_role_alias_name", "-p", "y", "-ar", "us-east-1", "-ss",
                "false");
        greengrassSetup.parseArgs();
        greengrassSetup.setDeviceProvisioningHelper(deviceProvisioningHelper);
        greengrassSetup.provision(kernel);
        verify(deviceProvisioningHelper, times(1)).createThing(any(), any());
        verify(deviceProvisioningHelper, times(1)).addThingToGroup(any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).updateKernelConfigWithIotConfiguration(any(), any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).setupIoTRoleForTes(any(), any(), any());
        verify(deviceProvisioningHelper, times(1)).createAndAttachRolePolicy(any(), any());
    }

    @Test
    void GIVEN_setup_script_WHEN_script_is_used_with_unknown_args_THEN_script_fails(ExtensionContext context) {
        ignoreExceptionUltimateCauseWithMessage(context, "Undefined command line argument: -x");
        assertThrows(RuntimeException.class,
                () -> new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "-i",
                        "mock_config_path", "-r", "mock_root", "-tn", "mock_thing_name", "-x", "mock_wrong_arg_value",
                        "-trn", "mock_tes_role_name", "-ar", "us-east-1", "-ss", "false").parseArgs());
    }

    @Test
    void GIVEN_setup_script_WHEN_dry_run_THEN_kernel_not_launched() throws Exception {
        greengrassSetup =
                new GreengrassSetup(System.out, System.err, deviceProvisioningHelper, platform, kernel, "--config",
                        "mock_config_path", "--root", "mock_root", "--start", "false", "-ar", "us-east-1", "-ss",
                        "false");

        greengrassSetup.parseArgs();
        greengrassSetup.performSetup();
        verify(kernel, times(0)).launch();
    }
}
