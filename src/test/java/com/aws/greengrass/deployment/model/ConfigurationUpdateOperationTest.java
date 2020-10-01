/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

class ConfigurationUpdateOperationTest {
    ObjectMapper mapper = new ObjectMapper();


    @Test
    void getValue() throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.with("path1").put("key2", "val2");

        ObjectNode node2 = mapper.createObjectNode();
        node2.with("path1").put("key3", "val3");
        node2.with("path1").with("path2").put("key3", "val3");


        ObjectNode val = mapper.createObjectNode();
        val.with("path1").put("key2", "defaultV2");

        System.out.println(val);

        //        setJsonPointerValue2(node, JsonPointer.valueOf("/test/deep/deep2"), mapper.createObjectNode().put("key1", "val1"));

        Map<String, Object> map1 = mapper.convertValue(node, new TypeReference<Map<String, Object>>(){});

        Map<String, Object> map2 = mapper.convertValue(node2, new TypeReference<Map<String, Object>>(){});

        //        ObjectNode val = mapper.createObjectNode().with("path2").put("key1", "val1");

//        System.out.println(nod3.toPrettyString());
//        JsonNode node2 = mapper.readerForUpdating(nod3).readValue(node);
        deepMerge(map1, map2);
        System.out.println(map1);

        reset(map1, val, JsonPointer.valueOf("/path1"));
        System.out.println(map1);

    }

    void setJsonPointerValue2(final ObjectNode node, final JsonPointer pointer, final JsonNode value) {
        ObjectNode resultNode = createPointer(node, pointer);
//        node.putAll()
    }

    private  Map reset(Map original, JsonNode value, JsonPointer pointer) {
        JsonNode node = mapper.convertValue(original, JsonNode.class);

        ((ObjectNode) node.at(pointer)).setAll((ObjectNode) value.at(pointer));

        return mapper.convertValue(node, Map.class);
    }

    private Map deepMerge(Map original, Map newMap) {
        for (Object key : newMap.keySet()) {
            if (newMap.get(key) instanceof Map && original.get(key) instanceof Map) {
                Map originalChild = (Map) original.get(key);
                Map newChild = (Map) newMap.get(key);
                original.put(key, deepMerge(originalChild, newChild));
            } else if (newMap.get(key) instanceof List && original.get(key) instanceof List) {
                System.out.println("ist is not supported");
            } else {
                original.put(key, newMap.get(key));
            }
        }
        return original;
    }

    ObjectNode createPointer(final ObjectNode node, final JsonPointer pointer) {

        JsonPointer ParentPointer = pointer.head();
        JsonNode target = node.at(ParentPointer);
        if (target.isMissingNode() || target.isNull()) {
            ObjectNode resultNode = createPointer(node, pointer.head());
            return resultNode.with(pointer.last().getMatchingProperty());
        } else {
            // object
            return node.with(pointer.getMatchingProperty());
        }

    }


    void setJsonPointerValue(final ObjectNode node, final JsonPointer pointer, final JsonNode value) {

        JsonPointer parentPointer = pointer.head();
        JsonNode parentNode = node.at(parentPointer);
        String fieldName = pointer.last().toString().substring(1);
        if (parentNode.isMissingNode() || parentNode.isNull()) {
            parentNode = node.with(fieldName);
            setJsonPointerValue((ObjectNode) parentNode, pointer.head(), value); // recursively reconstruct hierarchy
        }

        else if (parentNode.isObject()) {
            ((ObjectNode) parentNode).set(fieldName, value);
        } else {
            throw new IllegalArgumentException("`" + fieldName + "` can't be set for parent node `" + parentPointer + "` because parent is not a container but " + parentNode.getNodeType().name());
        }
    }
}