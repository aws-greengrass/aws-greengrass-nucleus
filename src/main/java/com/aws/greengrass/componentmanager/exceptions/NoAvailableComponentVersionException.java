/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import com.vdurmont.semver4j.Requirement;

import java.util.Map;

public class NoAvailableComponentVersionException extends PackagingException {

    static final long serialVersionUID = -3387516993124229948L;

    public NoAvailableComponentVersionException(String initialMessage, String componentName, Requirement requirement) {
        super(String.format("%s Component: %s version: %s", initialMessage.trim(), componentName,
                requirement.toString()));
    }

    public NoAvailableComponentVersionException(String initialMessage, String componentName,
                                                Map<String, Requirement> requirements) {
        super(makeMessage(initialMessage, componentName, requirements));
    }

    public NoAvailableComponentVersionException(String initialMessage, String componentName,
                                                Map<String, Requirement> requirements, Throwable cause) {
        super(makeMessage(initialMessage, componentName, requirements), cause);
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
