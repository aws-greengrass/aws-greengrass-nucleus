/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class ComponentUpdatePolicy {

    @JsonProperty("Timeout")
    private Integer timeout = 60;
    @JsonProperty("ComponentUpdatePolicyAction")
    private ComponentUpdatePolicyAction componentUpdatePolicyAction = ComponentUpdatePolicyAction.NOTIFY_COMPONENTS;
}
