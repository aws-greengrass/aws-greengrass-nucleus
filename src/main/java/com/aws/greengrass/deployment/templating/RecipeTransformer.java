/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

import com.amazon.aws.iot.greengrass.component.common.ComponentRecipe;
import com.aws.greengrass.deployment.templating.exceptions.IllegalTemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.MissingTemplateParameterException;
import com.aws.greengrass.deployment.templating.exceptions.TemplateParameterTypeMismatchException;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.annotation.Nullable;

// TODO: turn this into an abstract class since there's a lot of boilerplate with schema validation, etc
/**
 * Interface representing a runnable that takes as input(s) minimized recipe(s) and generates full recipe(s) and
 * artifacts. Only maintains state for the template.
 */
public abstract class RecipeTransformer {
    // valid json data types
    public static final String STRING_TYPE = "string";
    public static final String NUMBER_TYPE = "number";
    public static final String OBJECT_TYPE = "object";
    public static final String ARRAY_TYPE = "array";
    public static final String BOOLEAN_TYPE = "boolean";
    public static final String NULL_TYPE = "null";

    private JsonNode templateSchema;
    private JsonNode effectiveDefaultConfig;

    /**
     * Constructor. One class instance for each template; instances are shared between parameter files for the same
     * template.
     * @param templateRecipe to extract default params, param schema.
     * @param templateConfig the running/custom config for the template.
     * @throws TemplateParameterException if the template recipe or custom config is malformed.
     */
    public RecipeTransformer(ComponentRecipe templateRecipe, JsonNode templateConfig)
            throws TemplateParameterException {
        templateSchema = initTemplateSchema();
        templateConfig(templateRecipe.getComponentConfiguration().getDefaultConfiguration(), templateConfig);
    }

    /**
     * Workaround to "abstract" template schema field. Initializes the templateSchema field.
     */
    protected abstract JsonNode initTemplateSchema() throws TemplateParameterException;

    /**
     * Stateless expansion for one component.
     * @param parameterFile the parameter file for the component.
     * @param componentConfig any custom/runtime config mapping for that component.
     * @return a pair. See the declaration for {@link #transform(ComponentRecipe, JsonNode) transform} for more details.
     * @throws RecipeTransformerException if the provided parameters violate the template schema.
     */
    public Pair<ComponentRecipe, List<Path>> execute(ComponentRecipe parameterFile, JsonNode componentConfig)
            throws RecipeTransformerException {
        JsonNode effectiveComponentConfig = mergeAndValidateComponentConfig(parameterFile, componentConfig);
        return transform(parameterFile, effectiveComponentConfig);
    }

    /**
     * Transforms the parameter file into a full recipe.
     * @param paramFile the parameter file object.
     * @param componentConfig the effective component config to use during expansion.
     * @return a pair consisting of {newRecipe, artifactsToCopy}. newRecipe is the expanded recipe file;
     * artifactsToCopy is a list of artifacts to inject into the expanded component's runtime artifact directory.
     * @throws RecipeTransformerException if there is any error with the transformation.
     */
    public abstract Pair<ComponentRecipe, List<Path>> transform(ComponentRecipe paramFile, JsonNode componentConfig)
            throws RecipeTransformerException;

    /**
     * Note the configuration of the template itself. Validates provided defaults and custom configs.
     * @param defaultConfig the DefaultConfiguration recipe key.
     * @param activeConfig a map of configuration values specified in lieu of defaults.
     * @throws TemplateParameterException if the template recipe file or given configuration is malformed.
     */
    @SuppressWarnings({"PMD.ForLoopCanBeForeach", "PMD.AvoidDuplicateLiterals"})
    protected void templateConfig(JsonNode defaultConfig, JsonNode activeConfig) throws
            TemplateParameterException {
        // TODO: validate schema in template matches internal schema


        // validate hard-coded/user-provided "default configs"
        JsonNode defaultNode = mergeParams(defaultConfig.deepCopy().get("parameters"), activeConfig)
                .orElseGet(JsonNodeFactory.instance::objectNode);
        // check both ways
        for (Iterator<String> it = templateSchema.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!activeConfig.has(field)
                    && !(getTitleInsensitive(templateSchema.get(field), "required")).asBoolean()) {
                throw new MissingTemplateParameterException("Template does not provide default for optional "
                        + "parameter: " + field);
            }
            if (getTitleInsensitive(templateSchema.get(field), "required").asBoolean()) {
                ((ObjectNode)defaultNode).remove(field);
                continue;
            }
            JsonNode defaultVal = activeConfig.get(field);
            if (nodeType(templateSchema.get(field).get("type").asText()) != defaultVal.getNodeType()) {
                throw new TemplateParameterTypeMismatchException("Template default value does not match schema. "
                        + "Expected " + nodeType(templateSchema.get(field).get("type").asText()) + " but got "
                        + defaultNode.getNodeType());
            }
        }
        for (Iterator<String> it = defaultNode.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!templateSchema.has(field)) {
                throw new IllegalTemplateParameterException("Template declared parameter not found in schema: "
                        + field);
            }
        }

        effectiveDefaultConfig = defaultNode;
    }

    protected JsonNode mergeAndValidateComponentConfig(ComponentRecipe paramFile, JsonNode componentConfig)
            throws RecipeTransformerException{
        JsonNode paramNode = componentConfig.deepCopy();
        paramNode =
                mergeParams(paramFile.getComponentConfiguration().getDefaultConfiguration(), paramNode)
                        .orElseGet(JsonNodeFactory.instance::objectNode);
        JsonNode mergedParams = mergeParams(effectiveDefaultConfig, paramNode).get();
        try {
            validateParams(mergedParams);
        } catch (TemplateParameterException e) {
            throw new RecipeTransformerException("Configuration does not match required schema", e);
        }
        return mergedParams;
    }


    /*****************************************
      Utility functions for validation/etc
     *****************************************/
    @SuppressWarnings("PMD.ForLoopCanBeForeach")
    void validateParams(JsonNode params) throws TemplateParameterException {
        // check both ways
        for (Iterator<String> it = templateSchema.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!params.has(field)) { // we validate template defaults, so this can only happen if a required param
                // is not given
                throw new MissingTemplateParameterException("Configuration does not specify required parameter: "
                        + field);
            }
            JsonNodeType paramType = params.get(field).getNodeType();
            if (nodeType(templateSchema.get(field).get("type").asText()) != paramType) {
                throw new TemplateParameterTypeMismatchException("Provided parameter does not satisfy template schema. "
                        + "Expected " + nodeType(templateSchema.get(field).get("type").asText()) + " but got "
                        + paramType);
            }
        }
        for (Iterator<String> it = params.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!templateSchema.has(field)) {
                throw new IllegalTemplateParameterException("Configuration declared parameter not found in schema: "
                        + field);
            }
        }
    }

    // merge default-provided parameters into custom component specifications (custom takes precedence)
    protected static Optional<JsonNode> mergeParams(@Nullable JsonNode defaultVal, @Nullable JsonNode customVal) {
        if (defaultVal == null) {
            return Optional.ofNullable(customVal);
        }
        if (customVal == null) {
            return Optional.of(defaultVal);
        }

        JsonNode retval = customVal.deepCopy();
        Iterator<String> fieldNames = defaultVal.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (hasTitleInsensitive(customVal, fieldName)) { // TODO: how do we resolve field capitalization???
                ((ObjectNode)retval).set(fieldName, customVal.get(fieldName)); // non-recursive
            } else {
                ((ObjectNode)retval).set(fieldName, defaultVal.get(fieldName));
            }
        }
        return Optional.of(retval);
    }

    // equivalent to asking for both "fieldName" and "FieldName".
    // If one is declared but the other isn't, return the one declared.
    // If neither are declared, return null.
    // If both are declared, return the one asked for.
    // does no error checking either. good luck!
    protected static JsonNode getTitleInsensitive(JsonNode node, String fieldName) {
        if (node.has(fieldName)) {
            return node.get(fieldName);
        }
        char inverseCaseFirstChar;
        if (Character.isUpperCase(fieldName.charAt(0))) {
            inverseCaseFirstChar = Character.toLowerCase(fieldName.charAt(0));
        } else {
            inverseCaseFirstChar = Character.toUpperCase(fieldName.charAt(0));
        }
        String inverseCaseFieldName = inverseCaseFirstChar + fieldName.substring(1);
        return node.get(inverseCaseFieldName);
    }

    // similar, but for has instead of get
    protected static boolean hasTitleInsensitive(JsonNode node, String fieldName) {
        if (node.has(fieldName)) {
            return true;
        }
        char inverseCaseFirstChar;
        if (Character.isUpperCase(fieldName.charAt(0))) {
            inverseCaseFirstChar = Character.toLowerCase(fieldName.charAt(0));
        } else {
            inverseCaseFirstChar = Character.toUpperCase(fieldName.charAt(0));
        }
        String inverseCaseFieldName = inverseCaseFirstChar + fieldName.substring(1);
        return node.has(inverseCaseFieldName);
    }

    // Utility to get the node type from a string. Useful when parsing type from JSON.
    protected static JsonNodeType nodeType(String typeString) {
        String lowercased = typeString.toLowerCase(Locale.ROOT);
        switch (lowercased) {
            case STRING_TYPE: return JsonNodeType.STRING;
            case NUMBER_TYPE: return JsonNodeType.NUMBER;
            case OBJECT_TYPE: return JsonNodeType.OBJECT;
            case ARRAY_TYPE: return JsonNodeType.ARRAY;
            case BOOLEAN_TYPE: return JsonNodeType.BOOLEAN;
            case NULL_TYPE: return JsonNodeType.NULL;
            default: return JsonNodeType.MISSING;
        }
    }
}
