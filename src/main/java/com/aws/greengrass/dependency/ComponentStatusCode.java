/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.dependency;

import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.Lifecycle;
import com.aws.greengrass.util.Utils;
import lombok.Getter;

/**
 * The states in the lifecycle of a service.
 */
public enum ComponentStatusCode {

    NONE(""),
    INSTALL_ERROR("An error occurred during installation.",
            "The install script exited with code %s."),
    INSTALL_CONFIG_NOT_VALID(
            "Installation couldn't be completed. The structure of the installation section of the recipe is "
                    + "not valid. Check the install section and try your request again."),
    INSTALL_IO_ERROR("There was an I/O error during installation. Check the component log for more information."),
    INSTALL_MISSING_DEFAULT_RUNWITH("Couldn't determine the user or group to use when installing the component. Check"
            + " the runWith section of your recipe and try your request again."),
    INSTALL_TIMEOUT(
            "Install script didn't finish within the timeout period. Increase the timeout to give it more "
                    + "time to run or check your code."),
    STARTUP_ERROR("An error occurred during startup.",
            "The startup script exited with code %s."),
    STARTUP_CONFIG_NOT_VALID(
            "The component couldn't be started. The structure of the startup section of the recipe is not valid. "
                    + "Check the startup section and try your request again."),
    STARTUP_IO_ERROR("There was an I/O error starting the component. Check the component log for more information."),
    STARTUP_MISSING_DEFAULT_RUNWITH("Couldn't determine the user or group to use when starting the component. "
            + "Check the runWith section of your recipe and try your request again."),
    STARTUP_TIMEOUT(
            "Startup script didn't finish within the timeout period. Increase the timeout to give it more "
                    + "time to run or check your code."),
    RUN_ERROR("An error occurred while running the component.",
            "The run script exited with code %s."),
    RUN_MISSING_DEFAULT_RUNWITH("Couldn't determine the user or group to use when running the component. Check"
            + " the runWith section of your recipe and try your request again."),
    RUN_CONFIG_NOT_VALID(
            "The component couldn't run. The structure of the run section of the recipe is not valid. Check"
                    + " the run section and try your request again."),
    RUN_IO_ERROR("There was an I/O error running the component. Check the component log for more information."),
    RUN_TIMEOUT("Run script didn't finish within the timeout period. Increase the timeout to give it more time to run "
            + "or check your code."),
    SHUTDOWN_ERROR("An error occurred while shutting down the component.",
            "The shutdown script exited with code %s."),
    SHUTDOWN_TIMEOUT("Shutdown script didn't finish within the timeout period. Increase the timeout to give it more "
            + "time to run or check your code.");

    @Getter
    private String description;

    private String exitCodeMessage;

    ComponentStatusCode(String description) {
        this.description = description;
    }

    ComponentStatusCode(String description, String exitCodeMessage) {
        this.description = description;
        this.exitCodeMessage = exitCodeMessage;
    }

    /**
     * Returns formatted description with the provided exit code, returns only description if exit code is not
     * applicable to the status code.
     *
     * @param exit exit code to use to generate the message
     * @return formatted description with the exit code
     */
    public String getDescriptionWithExitCode(int exit) {
        if (Utils.isNotEmpty(exitCodeMessage)) {
            return String.format("%s %s", description, String.format(exitCodeMessage, exit));
        } else {
            return description;
        }
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
                return INSTALL_CONFIG_NOT_VALID;
            case Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC:
                return STARTUP_CONFIG_NOT_VALID;
            case GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC:
                return RUN_CONFIG_NOT_VALID;
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
                return INSTALL_ERROR;
            case STARTING:
                return STARTUP_ERROR;
            case RUNNING:
                return RUN_ERROR;
            case STOPPING:
                return SHUTDOWN_ERROR;
            default:
                return NONE;
        }
    }
}
