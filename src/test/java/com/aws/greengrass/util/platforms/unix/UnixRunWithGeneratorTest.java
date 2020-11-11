/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.RunWith;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Optional;

import static com.aws.greengrass.deployment.DeviceConfiguration.RUN_WITH_DEFAULT_POSIX_SHELL;
import static com.aws.greengrass.deployment.DeviceConfiguration.RUN_WITH_DEFAULT_POSIX_USER;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.POSIX_USER_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.doReturn;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class UnixRunWithGeneratorTest {


    UnixRunWithGenerator generator;

    @Mock
    DeviceConfiguration deviceConfig;

    @Mock
    UnixPlatform platform;

    @Mock
    Topics config;

    @Mock
    Context context;

    @Mock
    UnixUserAttributes userAttr;

    @Mock
    UnixGroupAttributes groupAttr;

    @BeforeEach
    void setupGenerator() {
        generator = new UnixRunWithGenerator(platform);
    }

    @Test
    public void GIVEN_component_run_with_uid_gid_WHEN_generate_THEN_use_component() {
        doReturn(Topic.of(context, POSIX_USER_KEY, "123:456"))
                .when(config).find(RUN_WITH_NAMESPACE_TOPIC, POSIX_USER_KEY);
        Optional<RunWith> result = generator.generate(deviceConfig, config);
        assertThat("RunWith must be generated", result.isPresent(), is(true));
        assertThat("user", result.get().getUser(), is("123"));
        assertThat("group", result.get().getGroup(), is("456"));
        assertThat("default", result.get().isDefault(), is(false));
    }

    @Test
    public void GIVEN_component_run_with_user_group_WHEN_generate_THEN_use_component() {
        doReturn(Topic.of(context, POSIX_USER_KEY, "foo:bar"))
                .when(config).find(RUN_WITH_NAMESPACE_TOPIC, POSIX_USER_KEY);
        Optional<RunWith> result = generator.generate(deviceConfig, config);
        assertThat("RunWith must be generated", result.isPresent(), is(true));
        assertThat("user", result.get().getUser(), is("foo"));
        assertThat("group", result.get().getGroup(), is("bar"));
        assertThat("default", result.get().isDefault(), is(false));
    }

    @Test
    public void GIVEN_component_run_with_user_WHEN_generate_THEN_use_component() throws IOException {
        doReturn(Topic.of(context, POSIX_USER_KEY, "foo"))
                .when(config).find(RUN_WITH_NAMESPACE_TOPIC, POSIX_USER_KEY);

        doReturn(userAttr).when(platform).lookupUserByIdentifier("foo");
        doReturn(Optional.of(123L)).when(userAttr).getPrimaryGID();

        Optional<RunWith> result = generator.generate(deviceConfig, config);
        assertThat("RunWith must be generated", result.isPresent(), is(true));
        assertThat("user", result.get().getUser(), is("foo"));
        assertThat("group", result.get().getGroup(), is("123"));
        assertThat("default", result.get().isDefault(), is(false));
    }

    @Test
    public void GIVEN_component_run_with_user_WHEN_no_group_generate_THEN_return_empty() throws IOException {
        doReturn(Topic.of(context, POSIX_USER_KEY, "foo"))
                .when(config).find(RUN_WITH_NAMESPACE_TOPIC, POSIX_USER_KEY);

        doReturn(userAttr).when(platform).lookupUserByIdentifier("foo");
        doReturn(Optional.empty()).when(userAttr).getPrimaryGID();

        Optional<RunWith> result = generator.generate(deviceConfig, config);
        assertThat("RunWith must not be generated", result.isPresent(), is(false));
    }

    @Test
    public void GIVEN_valid_default_run_with_user_WHEN_generate_and_group_exists_THEN_use_default()
            throws IOException  {
        doReturn(Topic.of(context, POSIX_USER_KEY, "foo"))
                .when(deviceConfig).getRunWithDefaultPosixUser();

        doReturn(userAttr).when(platform).lookupUserByIdentifier("foo");
        doReturn(Optional.of(123L)).when(userAttr).getPrimaryGID();

        Optional<RunWith> result = generator.generate(deviceConfig, config);
        assertThat("RunWith must be generated", result.isPresent(), is(true));
        assertThat("user", result.get().getUser(), is("foo"));
        assertThat("group", result.get().getGroup(), is("123"));
        assertThat("default", result.get().isDefault(), is(true));
    }

    @Test
    public void GIVEN_default_run_with_user_group_WHEN_generate_THEN_use_default() throws IOException {
        doReturn(Topic.of(context, RUN_WITH_DEFAULT_POSIX_USER, "foo:bar"))
                .when(deviceConfig).getRunWithDefaultPosixUser();
        doReturn(Topic.of(context,  RUN_WITH_DEFAULT_POSIX_SHELL, "/foo/bar"))
                .when(deviceConfig).getRunWithDefaultPosixShell();

        Optional<RunWith> result = generator.generate(deviceConfig, config);
        assertThat("RunWith must be generated", result.isPresent(), is(true));
        assertThat("user", result.get().getUser(), is("foo"));
        assertThat("group", result.get().getGroup(), is("bar"));
        assertThat("shell", result.get().getShell(), is("/foo/bar"));
        assertThat("default", result.get().isDefault(), is(true));
    }

    @Test
    public void GIVEN_is_root_WHEN_generate_THEN_return_empty() throws IOException {
        doReturn(userAttr).when(platform).lookupCurrentUser();
        doReturn(true).when(userAttr).isSuperUser();

        Optional<RunWith> result = generator.generate(deviceConfig, config);
        assertThat("RunWith must not be generated", result.isPresent(), is(false));
    }


    @Test
    public void GIVEN_non_root_WHEN_generate_THEN_use_user()throws IOException  {
        doReturn(userAttr).when(platform).lookupCurrentUser();
        doReturn(false).when(userAttr).isSuperUser();

        doReturn("foo").when(userAttr).getPrincipalName();
        doReturn(Optional.of(123L)).when(userAttr).getPrimaryGID();

        Optional<RunWith> result = generator.generate(deviceConfig, config);
        assertThat("RunWith must be generated", result.isPresent(), is(true));
        assertThat(result.get().getUser(), is("foo"));
        assertThat(result.get().getGroup(), is("123"));
        assertThat("default", result.get().isDefault(), is(false));
    }
}
