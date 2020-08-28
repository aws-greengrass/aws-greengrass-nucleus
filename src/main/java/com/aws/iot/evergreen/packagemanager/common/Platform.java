/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Value;

import java.util.Collection;
import java.util.HashSet;

@JsonDeserialize(builder = Platform.PlatformBuilder.class)
@Builder
@Value
public class Platform {
    public static final String ALL_KEYWORD = "all";

    @Builder.Default
    OS os = OS.ALL;
    // String osVersion;
    @Builder.Default
    Architecture architecture = Architecture.ALL;

    @JsonPOJOBuilder(withPrefix = "")
    public static class PlatformBuilder {
    }

    /**
     * Non customer-facing class. Keeps the OS hierarchy data.
     */
    @Getter
    public enum OS {
        ALL(null, ALL_KEYWORD),
        WINDOWS(ALL, "windows"),
        UNIX(ALL, "unix"),
        LINUX(UNIX, "linux"),
        FEDORA(LINUX, "fedora"),
        DEBIAN(LINUX, "debian"),
        UBUNTU(DEBIAN, "ubuntu"),
        RASPBIAN(DEBIAN, "raspbian"),
        DARWIN(UNIX, "darwin"),
        MAC_OS(DARWIN, "macos");

        @JsonValue
        private final String name;
        private final OS parent;
        private final Collection<OS> children;
        private final int rank;

        OS(OS parent, String name) {
            this.parent = parent;
            this.name = name;
            this.children = new HashSet<>();
            if (parent == null) {
                this.rank = 0;
            } else {
                this.rank = parent.getRank() + 1;
                parent.getChildren().add(this);
            }
        }

        /**
         * get OS enum from string value. Ignore case.
         * Unrecognized values will map to OS.ALL
         * @param value String of OS
         * @return OS enum
         */
        @JsonCreator
        public static OS getOS(String value) {
            // "any" and "all" keyword are both accepted in recipe.
            if ("any".equalsIgnoreCase(value)) {
                return OS.ALL;
            }

            for (OS os : values()) {
                if (os.getName().equalsIgnoreCase(value)) {
                    return os;
                }
            }
            // TODO: throw exception of unrecognized OS
            return OS.ALL;
        }
    }

    /**
     * Non customer-facing class. Currently only has name field.
     */
    @Getter
    @AllArgsConstructor
    public enum Architecture {
        ALL(null, ALL_KEYWORD),
        AMD64(ALL, "amd64"),
        ARM(ALL, "arm");

        @JsonValue
        private final String name;
        private final Architecture parent;
        private final Collection<Architecture> children;
        private final int rank;

        Architecture(Architecture parent, String name) {
            this.parent = parent;
            this.name = name;
            this.children = new HashSet<>();
            if (parent == null) {
                this.rank = 0;
            } else {
                this.rank = parent.getRank() + 1;
                parent.getChildren().add(this);
            }
        }

        /**
         * get Architecture enum from string value. Ignore case.
         * Unrecognized values will map to Architecture.ALL
         * @param value String of Architecture
         * @return Architecture enum
         */
        @JsonCreator
        public static Architecture getArch(String value) {
            if ("any".equalsIgnoreCase(value)) {
                // "any" and "all" keyword are both accepted in recipe.
                return Architecture.ALL;
            }

            for (Architecture arch : values()) {
                if (arch.getName().equalsIgnoreCase(value)) {
                    return arch;
                }
            }
            // TODO: throw exception
            return Architecture.ALL;
        }
    }
}
