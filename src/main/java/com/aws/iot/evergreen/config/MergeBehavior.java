/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

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
 *   leafKey1:val1
 *   subKey1:
 *     leafKey1:val1
 *     subKey2:
 *       leafKey2:val2
 * bar:
 *   key1:val1
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
 * Resulting config:
 * --
 * key1:
 * foo:
 * bar:
 *   key1:val1
 * baz:
 *   key1:val1
 */
@AllArgsConstructor
@Getter
@Data
public class MergeBehavior {
    public static final String WILD_CARD = "*";
    public static final MergeBehavior MERGE_ALL;
    public static final MergeBehavior REPLACE_ALL;

    static {
        MERGE_ALL = new MergeBehavior(UpdateBehaviorEnum.MERGE, new HashMap<>());
        REPLACE_ALL = new MergeBehavior(UpdateBehaviorEnum.REPLACE, new HashMap<>());
    }

    public enum UpdateBehaviorEnum {
        MERGE, REPLACE;
    }

    private UpdateBehaviorEnum defaultBehavior;
    private Map<String, MergeBehavior> childOverride;

    public MergeBehavior(UpdateBehaviorEnum defaultBehavior) {
        this.defaultBehavior = defaultBehavior;
        this.childOverride = new HashMap<>();
    }
}
