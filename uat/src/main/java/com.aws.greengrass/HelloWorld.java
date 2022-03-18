/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass;

public class HelloWorld {
    public static final String COMPONENT_NAME_SYS_PROP = "componentName";

    public static void helloWorld() {
        System.out.println("Hello World!!");
    }

    public static void helloWorldUpdated() {
        System.out.println("Hello World Updated!!");
    }

    /**
     * Main method will call the required com.aws.greengrass.testing.components.cloudcomponent.HelloWorld artifact.
     *
     * @param args System Arguments
     */
    public static void main(String[] args) {
        String componentName = System.getProperty(COMPONENT_NAME_SYS_PROP);
        if (componentName.equals("HelloWorld")) {
            helloWorld();
        } else if (componentName.equals("HelloWorldUpdated")) {
            helloWorldUpdated();
        }
    }



}