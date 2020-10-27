/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

/**
 * Interface for decorating a command with another command.
 */
public interface CommandDecorator {
    /**
     * Decorate a command.
     *
     * @param command command to decorate.
     * @return a decorated command.
     */
    String[] decorate(String... command);
}
