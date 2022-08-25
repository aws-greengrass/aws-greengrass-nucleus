/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import com.vdurmont.semver4j.Requirement;

import java.util.Map;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.COMPONENT_VERSION_REQUIREMENTS_NOT_MET;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.INSTALLED_COMPONENT_NOT_FOUND;
import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.NO_AVAILABLE_COMPONENT_VERSION;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class NoAvailableComponentVersionException extends PackagingException {

    static final long serialVersionUID = -3387516993124229948L;

    public NoAvailableComponentVersionException(String initialMessage, String componentName, Requirement requirement) {
        // this constructor is only used when loading active components
        super(String.format("%s Component: %s version: %s", initialMessage.trim(), componentName,
                requirement.toString()));
        super.getErrorCodes().add(NO_AVAILABLE_COMPONENT_VERSION);
        super.getErrorCodes().add(INSTALLED_COMPONENT_NOT_FOUND);
    }

    public NoAvailableComponentVersionException(String initialMessage, String componentName,
                                                Map<String, Requirement> requirements) {
        super(makeMessage(initialMessage, componentName, requirements));
        super.getErrorCodes().add(NO_AVAILABLE_COMPONENT_VERSION);
        super.getErrorCodes().add(COMPONENT_VERSION_REQUIREMENTS_NOT_MET);
    }

    public NoAvailableComponentVersionException(String initialMessage, String componentName,
                                                Map<String, Requirement> requirements, Throwable cause) {
        super(makeMessage(initialMessage, componentName, requirements), cause);
        super.getErrorCodes().add(NO_AVAILABLE_COMPONENT_VERSION);
        super.getErrorCodes().add(COMPONENT_VERSION_REQUIREMENTS_NOT_MET);
    }

    private static String makeMessage(String initialMessage, String componentName,
                                      Map<String, Requirement> requirements) {
        StringBuilder sb = new StringBuilder(initialMessage.trim());
        sb.append(" Check whether the version constraints conflict and that the component exists in your AWS "
                        + "account with a version that matches the version constraints. "
                        + "If the version constraints conflict, revise deployments to resolve the conflict. Component ")
                .append(componentName).append(" version constraints:");

        for (Map.Entry<String, Requirement> req : requirements.entrySet()) {
            sb.append(' ').append(req.getKey()).append(" requires ").append(req.getValue().toString()).append(',');
        }
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }
        sb.append('.');

        return sb.toString();
    }
}
