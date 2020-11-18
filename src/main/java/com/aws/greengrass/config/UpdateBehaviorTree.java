/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

import java.util.Collections;
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

    private final UpdateBehavior behavior;
    private final Map<String, UpdateBehaviorTree> childOverride;
    private final long timestampToUse;

    /**
     * Create a mutable behavior tree with some behavior and a timestamp.
     *
     * @param behavior behavior to use when merging this and child nodes
     * @param timestamp       timestamp to use for this and child nodes
     */
    public UpdateBehaviorTree(UpdateBehavior behavior, long timestamp) {
        this(behavior, timestamp, new HashMap<>());
    }

    /**
     * Create a behavior tree with some behavior, timestamp, and map of child behaviors.
     *
     * @param behavior        behavior to use when merging this and child nodes
     * @param timestamp       timestamp to use for this and child nodes
     * @param childOverride   initial map to use to override children
     */
    protected UpdateBehaviorTree(UpdateBehavior behavior, long timestamp,
                                Map<String, UpdateBehaviorTree> childOverride) {
        this.behavior = behavior;
        this.timestampToUse = timestamp;
        this.childOverride = childOverride;
    }

    /**
     * Retrieve child behavior to use when behavior has not been overridden. Note that sub-class of
     * {@link UpdateBehaviorTree} is allowed to override this.
     *
     * @return child behavior to use
     */
    protected UpdateBehaviorTree getDefaultChildBehavior() {
        return new PrunedUpdateBehaviorTree(this.behavior, timestampToUse);
    }

    /**
     * Get the behavior for some subtree with a given key.
     *
     * @param key Name of the subtree
     * @return the behavior(s) to use for this subtree
     */
    public UpdateBehaviorTree getChildBehavior(String key) {
        UpdateBehaviorTree behavior = childOverride.get(key);
        if (behavior != null) {
            return behavior;
        }
        behavior = childOverride.get(WILDCARD);
        if (behavior != null) {
            return behavior;
        }
        return getDefaultChildBehavior();
    }

    /**
     * This transitively applies to subtree without needing to recursively create objects.
     */
    private static final class PrunedUpdateBehaviorTree extends UpdateBehaviorTree {
        private static final Map<String, UpdateBehaviorTree> DEFAULT_CHILD_BEHAVIOR = Collections.emptyMap();

        public PrunedUpdateBehaviorTree(UpdateBehavior behavior, long timestampToUse) {
            super(behavior, timestampToUse, DEFAULT_CHILD_BEHAVIOR);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public UpdateBehaviorTree getDefaultChildBehavior() {
            return this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public UpdateBehaviorTree getChildBehavior(String key) {
            return this;
        }
    }
}
