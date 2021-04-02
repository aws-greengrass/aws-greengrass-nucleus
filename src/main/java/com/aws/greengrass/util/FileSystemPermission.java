/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
@AllArgsConstructor
public class FileSystemPermission {
    String ownerUser;
    String ownerGroup;

    boolean ownerRead;
    boolean ownerWrite;
    boolean ownerExecute;

    boolean groupRead;
    boolean groupWrite;
    boolean groupExecute;

    boolean otherRead;
    boolean otherWrite;
    boolean otherExecute;

    public enum Option {
        /**
         * Apply permissions all the child directories and files.
         */
        Recurse,
        /**
         * Set owner.
         */
        SetOwner,
        /**
         * Set file mode (read/write/execute).
         */
        SetMode
    }
}
