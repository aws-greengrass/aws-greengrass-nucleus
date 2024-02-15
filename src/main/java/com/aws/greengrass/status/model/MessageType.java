/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status.model;


public enum MessageType {
    COMPLETE,
    PARTIAL;

    /**
     * Get MessageStatus from MessageType.
     *
     * @param trigger Trigger of FSS update
     * @return whether it's a complete or partial FSS update
     * @throws IllegalArgumentException invalid trigger
     */
    public static MessageType fromTrigger(Trigger trigger) {
        switch (trigger) {
            case LOCAL_DEPLOYMENT:
            case THING_DEPLOYMENT:
            case THING_GROUP_DEPLOYMENT:
            case COMPONENT_STATUS_CHANGE:
            case RECONNECT:
                return PARTIAL;
            case CADENCE:
            case NUCLEUS_LAUNCH:
            case NETWORK_RECONFIGURE:
                return COMPLETE;
            default:
                throw new IllegalArgumentException("Invalid trigger: " + trigger);
        }
    }
}
