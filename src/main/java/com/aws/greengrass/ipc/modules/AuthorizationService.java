/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.ipc.modules;

import com.aws.greengrass.authorization.AuthorizationIPCAgent;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.ipc.Startable;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCService;

import javax.inject.Inject;

public class AuthorizationService implements Startable, InjectionActions {

    @Inject
    private GreengrassCoreIPCService greengrassCoreIPCService;

    @Inject
    private AuthorizationIPCAgent authorizationIPCAgent;

    @Override
    public void startup() {
        greengrassCoreIPCService.setValidateAuthorizationTokenHandler(
                context -> authorizationIPCAgent.getValidateAuthorizationTokenOperationHandler(context));
    }
}
