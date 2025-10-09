/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import java.util.Map;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.COMPONENT_VERSION_REQUIREMENTS_NOT_MET;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.NO_AVAILABLE_COMPONENT_VERSION;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class IncompatiblePlatformClaimByComponentException extends PackagingException {

    static final long serialVersionUID = -3387516993124229948L;

    public IncompatiblePlatformClaimByComponentException(String initialMessage, String componentName,
            Map<String, String> platform) {
        super(makeMessage(initialMessage, componentName, platform));
        super.addErrorCode(NO_AVAILABLE_COMPONENT_VERSION);
        super.addErrorCode(COMPONENT_VERSION_REQUIREMENTS_NOT_MET);
    }

    private static String makeMessage(String initialMessage, String componentName,
            Map<String, String> platformRequirements) {
        StringBuilder sb = new StringBuilder(initialMessage.trim());
        sb.append(" Check whether the component platform specifications mentioned in its recipe match the "
                + "core device platform constraints. If the component is not supported on the core device "
                + "platform with classic runtime, revise deployments to resolve the conflict. Component '")
                .append(componentName)
                .append("' is incompatible with the core device(classic) platform requirements - ");
        for (Map.Entry<String, String> requirement : platformRequirements.entrySet()) {
            sb.append(' ').append(requirement.getKey()).append(':').append(requirement.getValue()).append(',');
        }
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }
        sb.append('.');

        return sb.toString();
    }
}
