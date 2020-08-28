/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader.model;

import lombok.Builder;
import lombok.Getter;
import lombok.Value;
import org.slf4j.event.Level;

import java.util.List;
import java.util.regex.Pattern;

@Builder
@Value
@Getter
public class ComponentLogFileInformation {
    private List<LogFileInformation> logFileInformationList;
    private String name;
    private Pattern multiLineStartPattern;
    private Level desiredLogLevel;
    private ComponentType componentType;
}
