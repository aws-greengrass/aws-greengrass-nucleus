/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.aws.greengrass.model.RunWithInfo;

import java.util.List;
import java.util.Map;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Builder
public class LocalOverrideRequest {
    String requestId;   // UUID
    long requestTimestamp;

    Map<String, String> componentsToMerge;  // name to version
    List<String> componentsToRemove; // remove just need name
    String groupName;

    @Deprecated
    Map<String, Map<String, Object>> componentNameToConfig;

    Map<String, ConfigurationUpdateOperation> configurationUpdate;

    Map<String, RunWithInfo> componentToRunWithInfo;
}
