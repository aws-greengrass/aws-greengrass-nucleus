/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.databind.JsonNode;

public class TemplateParameterBundle extends Pair<ComponentRecipe, JsonNode> {
    public TemplateParameterBundle(ComponentRecipe left, JsonNode right) {
        super(left, right);
    }
}
