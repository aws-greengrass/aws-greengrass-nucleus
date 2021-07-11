/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating.transformers;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.deployment.templating.RecipeTransformer;
import com.aws.greengrass.deployment.templating.TemplateParameterException;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer;

public class EchoTransformer extends RecipeTransformer {

    private static final String COMPONENT_DESCRIPTION = "Component expanded with EchoTransformer";
    private static final String COMPONENT_PUBLISHER = "Me";

    private static final String TEMPLATE_SCHEMA = "{\n" + "  \"param1\": {\n" + "    \"type\": \"string\",\n"
            + "    \"required\": true\n" + "  },\n" + "  \"param2\": {\n" + "    \"type\": \"string\",\n"
            + "    \"required\": true\n" + "  },\n" + "  \"resetParam1\": {\n" + "    \"type\": \"string\",\n"
            + "    \"required\": false\n" + "  },\n" + "  \"resetParam2\": {\n" + "    \"type\": \"string\",\n"
            + "    \"required\": true\n" + "  },\n" + "}";

    /**
     * Constructor. One class instance for each template; instances are shared between parameter files for the same
     * template.
     *
     * @param templateRecipe to extract default params, param schema.
     * @param templateConfig the running/custom config for the template.
     * @throws TemplateParameterException if the template recipe or custom config is malformed.
     */
    public EchoTransformer(ComponentRecipe templateRecipe, JsonNode templateConfig) throws TemplateParameterException {
        super(templateRecipe, templateConfig);
    }

    @Override
    protected JsonNode initTemplateSchema() throws TemplateParameterException{
        try {
            return getRecipeSerializer().readTree(TEMPLATE_SCHEMA);
        } catch (JsonProcessingException e) {
            throw new TemplateParameterException(e);
        }
    }

    // generate a component recipe from a list of well-behaved parameters
    @Override
    public Pair<ComponentRecipe, List<Path>> transform(ComponentRecipe paramFile, JsonNode componentConfig) {
        Map<String, Object> newLifecyle = new HashMap<>();
        String runString = "echo Param1: " + componentConfig.get("param1").asText() + " Param2: " + componentConfig.get(
                "param2").asText() + " ResetParam1: " + componentConfig.get("resetParam1").asText() + " ResetParam2: "
                + componentConfig.get("resetParam2").asText();
        newLifecyle.put("run", runString);

        ComponentRecipe newRecipe = ComponentRecipe.builder()
                .recipeFormatVersion(RecipeFormatVersion.JAN_25_2020)
                .componentName(paramFile.getComponentName())
                .componentVersion(paramFile.getComponentVersion())
                .componentDescription(COMPONENT_DESCRIPTION)
                .componentPublisher(COMPONENT_PUBLISHER)
                .componentType(ComponentType.GENERIC)
                .lifecycle(newLifecyle)
                .build();
        List<Path> artifactsToCopy = new ArrayList<>();
        return new Pair<>(newRecipe, artifactsToCopy);
    }
}
