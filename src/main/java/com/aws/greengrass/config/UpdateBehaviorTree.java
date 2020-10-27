/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/** A hierarchy data structure indicating merge behavior of entire config tree.
 * An example looks like below:
 * [MERGE]
 *   key1: [MERGE]
 *     subkey1: [REPLACE]
 *       subkey2: [MERGE]
 *   *: [REPLACE]
 *     subkey1: [MERGE]
 *       subkey2: [REPLACE]
 * <p>
 * Original config:
 * --
 * key1:
 *   otherKey: otherVal
 *   subKey1:
 *     leafKey1:val1
 *     subKey2:
 *       leafKey2:val2
 * foo:
 *   otherKey: otherVal
 *   subKey1:
 *     leafKey1:val1
 *     subKey2:
 *       leafKey2:val2
 * bar:
 *   key1:val1
 * </p>
 * <p>
 * Config to merge in:
 * --
 * key1:
 *   subKey1:
 *     subKey2:
 *       updatedLeafKey2: updatedVal2
 * foo:
 *   subKey1:
 *     subKey2:
 *       updatedLeafKey2: updatedVal2
 * baz:
 *   key1:val1
 * </p>
 * <p>
 * Resulting config:
 * --
 * key1:
 *   otherKey: otherVal (merged from original config)
 *   subKey1: (leafKey1 removed)
 *     subKey2:
 *       leafKey2:val2 (merged from original config)
 *       updatedLeafKey2: updatedVal2
 * foo: (otherKey removed)
 *   subKey1:
 *     leafKey1:val1 (merged from original config)
 *     subKey2: (leafKey2 removed)
 *       updatedLeafKey2: updatedVal2
 * bar: (merged from original config)
 *   key1:val1
 * baz: (merged from new config)
 *   key1:val1
 * </p>
 */
@AllArgsConstructor
@Getter
@Data
public class UpdateBehaviorTree {
    public static final String WILDCARD = "*";

    public enum UpdateBehavior {
        MERGE, REPLACE;
    }

    private final UpdateBehavior defaultBehavior;
    private final Map<String, UpdateBehaviorTree> childOverride;
    private final long timestampToUse;

    /**
     * Create a behavior tree with some behavior and a timestamp.
     *
     * @param defaultBehavior behavior to use when merging this and child nodes
     * @param timestamp       timestamp to use for this and child nodes
     */
    public UpdateBehaviorTree(UpdateBehavior defaultBehavior, long timestamp) {
        this.defaultBehavior = defaultBehavior;
        this.childOverride = new HashMap<>();
        this.timestampToUse = timestamp;
    }

    /**
     * Get the behavior for some subtree with a given key.
     *
     * @param key Name of the subtree
     * @return the behavior(s) to use for this subtree
     */
    public UpdateBehaviorTree getBehavior(String key) {
        if (childOverride.get(key) != null) {
            return childOverride.get(key);
        }
        if (childOverride.get(WILDCARD) != null) {
            return childOverride.get(WILDCARD);
        }
        return new UpdateBehaviorTree(defaultBehavior, timestampToUse);
    }

    // GG_NEEDS_REVIEW: TODO: add utility to parse from json/yaml
}
