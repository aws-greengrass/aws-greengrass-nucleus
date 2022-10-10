/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix.linux;

public enum CgroupV2FreezerState {
    THAWED(0),
    FROZEN(1);

    private int index;

    CgroupV2FreezerState(int index) {
        this.index = index;
    }

    /**
     * Get the index value associated with this CgroupV2FreezerState.
     *
     * @return the integer index value associated with this CgroupV2FreezerState.
     */
    public int getIndex() {
        return index;
    }
}
