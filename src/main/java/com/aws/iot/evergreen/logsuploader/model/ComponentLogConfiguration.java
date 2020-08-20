/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader.model;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.slf4j.event.Level;

import java.nio.file.Path;
import java.util.regex.Pattern;

@Builder
@Data
@Getter
public class ComponentLogConfiguration {
    private Pattern fileNameRegex;
    private Path directoryPath;
    private String name;
    @Builder.Default
    private Pattern multiLineStartPattern = Pattern.compile("^[^\\s]+(\\s+[^\\s]+)*$");
    @Builder.Default
    private Level minimumLogLevel = Level.INFO;
    private Long diskSpaceLimit;
    private boolean deleteLogFileAfterCloudUpload;
    private boolean uploadToCloudWatch;
    private ComponentType componentType;
}
