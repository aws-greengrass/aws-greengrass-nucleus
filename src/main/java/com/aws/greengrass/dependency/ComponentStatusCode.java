/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;

import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.Lifecycle;
import lombok.Getter;

/**
 * The states in the lifecycle of a service.
 */
public enum ComponentStatusCode {

    NONE(""),
    INSTALL_ERRORED("Error during install"),
    INSTALL_INTERRUPTED("Interrupted during install"),
    INSTALL_INVALID_CONFIG("Invalid configuration for install"),
    INSTALL_IO_ERROR("I/O error during install"),
    INSTALL_MISSING_DEFAULT_RUNWITH("Could not determine user/group to run with during install"),
    INSTALL_TIMEOUT("Timeout during install"),
    STARTUP_ERRORED("Error during startup"),
    STARTUP_INTERRUPTED("Interrupted during startup"),
    STARTUP_INVALID_CONFIG("Invalid configuration for startup"),
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
     * Get the appropriate status code for a missing runwith for the provided lifecycle topic name.
     *
     * @param lifecycleTopicName the lifecycle topic name
     * @return the status code for a missing runwith for the provided name
     */
    public static ComponentStatusCode getCodeMissingRunWithForState(String lifecycleTopicName) {
        switch (lifecycleTopicName) {
            case Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC:
                return INSTALL_MISSING_DEFAULT_RUNWITH;
            case Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC:
                return STARTUP_MISSING_DEFAULT_RUNWITH;
            case GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC:
                return RUN_MISSING_DEFAULT_RUNWITH;
            default:
                return NONE;
        }
    }

    /**
     * Get the appropriate status code for an invalid configuration for the provided lifecycle topic name.
     *
     * @param lifecycleTopicName the lifecycle topic name
     * @return the status code for an invalid configuration for the provided name
     */
    public static ComponentStatusCode getCodeInvalidConfigForState(String lifecycleTopicName) {
        switch (lifecycleTopicName) {
            case Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC:
                return INSTALL_INVALID_CONFIG;
            case Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC:
                return STARTUP_INVALID_CONFIG;
            case GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC:
                return RUN_INVALID_CONFIG;
            default:
                return NONE;
        }
    }

    /**
     * Get the appropriate status code for an I/O error for the provided lifecycle topic name.
     *
     * @param lifecycleTopicName the lifecycle topic name
     * @return the status code for an I/O error for the provided name
     */
    public static ComponentStatusCode getCodeIOErrorForState(String lifecycleTopicName) {
        switch (lifecycleTopicName) {
            case Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC:
                return INSTALL_IO_ERROR;
            case Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC:
                return STARTUP_IO_ERROR;
            case GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC:
                return RUN_IO_ERROR;
            default:
                return NONE;
        }
    }

    /**
     * Get the default status code associated with the provided lifecycle state transition.
     *
     * @param previousState the previous lifecycle state in the transition
     * @param nextState the next lifecycle state in the transition
     * @return the default status code associated with the transition from the old to the new state
     */
    public static ComponentStatusCode getDefaultStatusCodeForTransition(State previousState, State nextState) {
        ComponentStatusCode statusCode = ComponentStatusCode.NONE;
        if (State.ERRORED.equals(nextState)) {
            statusCode = ComponentStatusCode.getDefaultErrorCodeFrom(previousState);
        }
        return statusCode;
    }

    private static ComponentStatusCode getDefaultErrorCodeFrom(State previousState) {
        switch (previousState) {
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
