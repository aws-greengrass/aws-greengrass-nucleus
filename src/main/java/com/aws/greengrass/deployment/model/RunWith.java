/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Class to represent info about how the kernel should run lifecycle commands.
 */
@NoArgsConstructor
@EqualsAndHashCode
@SuppressWarnings("PMD.DataClass")
public class RunWith {

    private String posixUser = null;
    private String windowsUser = null;


    // Deserialization uses setters - boolean flags are set when setters are called so we can distringuise "null" values
    // from missing values
    private boolean callPosixUser = false;
    private boolean callWindowsUser = false;

    /**
     * Construct a new instance.
     *
     * @param posixUser posix user value.
     * @param windowsUser windows user value.
     */
    @Builder
    public RunWith(String posixUser, String windowsUser) {
        setPosixUser(posixUser);
        setWindowsUser(windowsUser);
    }

    /**
     * Set the posix user.
     *
     * @param value the posix user
     */
    @JsonSetter("PosixUser")
    public void setPosixUser(String value) {
        posixUser = value;
        callPosixUser = true;
    }

    /**
     * Get the posix user.
     *
     * @return the posix user
     */
    @JsonGetter("PosixUser")
    public String getPosixUser() {
        if (hasPosixUserValue()) {
            return posixUser;
        }
        return null;
    }

    /**
     * Set the windows user.
     *
     * @param value the windows user
     */
    @JsonSetter("WindowsUser")
    public void setWindowsUser(String value) {
        windowsUser = value;
        callWindowsUser = true;
    }

    /**
     * Get the windows user.
     *
     * @return the windows user
     */
    @JsonGetter("WindowsUser")
    public String getWindowsUser() {
        if (hasWindowsUserValue()) {
            return windowsUser;
        }
        return null;
    }

    /**
     * Return whether posixUser is set (even if the value could be null).
     *
     * @return true if posixUser is set
     */
    public boolean hasPosixUserValue() {
        return callPosixUser;
    }

    /**
     * Return whether windowsUser is set (even if the value could be null).
     *
     * @return true if windowsUser is set
     */
    public boolean hasWindowsUserValue() {
        return callWindowsUser;
    }

}
