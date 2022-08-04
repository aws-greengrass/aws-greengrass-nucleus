/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;

import lombok.Getter;

/**
 * The states in the lifecycle of a service.
 */
public enum ComponentStatusCode {

    NONE(""),
    INSTALL_ERRORED("Error during install"),
    INSTALL_INTERRUPTED("Interrupted during install"),
    INSTALL_INVALID_CONFIG("Invalid configuration for install"),
    INSTALL_IO_ERROR("I/O error during install."),
    INSTALL_MISSING_DEFAULT_RUNWITH("Could not determine user/group to run with during install"),
    INSTALL_TIMEOUT("Timeout during install"),
    STARTUP_ERRORED("Error during startup"),
    STARTUP_INTERRUPTED("Interrupted during startup"),
    STARTUP_IO_ERROR("I/O error during startup"),
    STARTUP_MISSING_DEFAULT_RUNWITH("Could not determine user/group to run with during startup"),
    STARTUP_TIMEOUT("Timeout during startup"),
    RUN_ERRORED("Error during run"),
    RUN_MISSING_DEFAULT_RUNWITH("Could not determine user/group to run with during run"),
    RUN_INVALID_CONFIG("Invalid configuration for run"),
    RUN_IO_ERROR("I/O error during run"),
    RUN_TIMEOUT("Timeout during run"),
    SHUTDOWN_ERRORED("Error during shutdown"),
    SHUTDOWN_INTERRUPTED("Interrupted during shutdown"),
    SHUTDOWN_TIMEOUT("Timeout during shutdown"),
    BOOTSTRAP_ERRORED("Error during bootstrap"),
    BOOTSTRAP_TIMEOUT("Timeout during bootstrap");

    @Getter
    private String description;

    ComponentStatusCode(String description) {
        this.description = description;
    }

    /**
     * Get the default error code associated with the provided lifecycle state.
     *
     * @param state the lifecycle state
     * @return the default error code associated with the lifecycle state
     */
    public static ComponentStatusCode getDefaultErrorCodeFrom(State state) {
        switch (state) {
            case NEW:
            case INSTALLED:
                return INSTALL_ERRORED;
            case STARTING:
                return STARTUP_ERRORED;
            case RUNNING:
                return RUN_ERRORED;
            case STOPPING:
                return SHUTDOWN_ERRORED;
            default:
                return NONE;
        }
    }
}
