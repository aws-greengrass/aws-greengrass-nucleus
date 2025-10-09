/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

/**
 * Decorate a command to run within a shell.
 */
public interface ShellDecorator extends CommandDecorator {
    /**
     * Set the shell to run with.
     * 
     * @param shell a path to a shell.
     * @return this.
     */
    ShellDecorator withShell(String shell);
}
