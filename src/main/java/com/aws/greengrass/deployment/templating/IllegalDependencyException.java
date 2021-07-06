/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

public class IllegalDependencyException extends Exception {
    private static final long serialVersionUID = 2405458707367333187L;

    public IllegalDependencyException(String message) {
        super(message);
    }
    public IllegalDependencyException(Throwable e) {
        super(e);
    }
}
