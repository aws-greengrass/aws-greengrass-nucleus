/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Represents a docker registry derived from a component artifact specification.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Registry {
    private static List<String> PRIVATE_ECR_REGISTRY_IDENTIFIERS = Arrays.asList("dkr.ecr", "amazonaws");
    private static String PUBLIC_ECR_REGISTRY_IDENTIFIER = "public.ecr.aws";

    @EqualsAndHashCode.Include
    private String endpoint;
    private ImageType type;
    private ImageSource source;
    @Setter
    private Credentials credentials;

    /**
     * Constructor.
     *
     * @param endpoint Registry endpoint
     */
    public Registry(String endpoint) {
        this.endpoint = endpoint;

        if (endpoint.contains(PUBLIC_ECR_REGISTRY_IDENTIFIER)) {
            this.source = ImageSource.ECR;
            this.type = ImageType.PUBLIC;
        } else if (containsAll(endpoint, PRIVATE_ECR_REGISTRY_IDENTIFIERS)) {
            this.source = ImageSource.ECR;
            this.type = ImageType.PRIVATE;
        } else {
            this.source = ImageSource.OTHER;
            this.type = ImageType.PUBLIC;
        }
    }

    private boolean containsAll(String str, List<String> subStrs) {
        for (String subStr : subStrs) {
            if (!str.contains(subStr)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get registry id.
     *
     * @return registry id
     */
    public String getRegistryId() {
        return this.isEcrRegistry() && this.isPrivateRegistry() ? this.endpoint.split("\\.")[0] : this.endpoint;
    }

    /**
     * Check if the registry is from ECR.
     *
     * @return evaluation result
     */
    public boolean isEcrRegistry() {
        return Registry.ImageSource.ECR.equals(source);
    }

    /**
     * Check if the image is private.
     *
     * @return evaluation result
     */
    public boolean isPrivateRegistry() {
        return Registry.ImageType.PRIVATE.equals(type);
    }

    public enum ImageType {
        PRIVATE, PUBLIC
    }

    public enum ImageSource {
        ECR, OTHER
    }

    /**
     * Registry credentials.
     */
    @Getter
    @AllArgsConstructor
    public static class Credentials {
        @NonNull
        private String username;
        @NonNull
        private String password;
        // For ECR, credential validity is 12 hrs, for other registries, credentials should be static
        private Instant expiresAt;

        /**
         * Check if credentials are valid at any point in time, if not they need to be refreshed.
         *
         * @return validity
         */
        public boolean stillValid() {
            return Objects.isNull(expiresAt) || Instant.now().compareTo(expiresAt) < 0;
        }

        /**
         * Refresh credential values with latest valid ones.
         *
         * @param username  username
         * @param password  password
         * @param expiresAt expiresAt
         */
        public void refresh(String username, String password, Instant expiresAt) {
            this.username = username;
            this.password = password;
            this.expiresAt = expiresAt;
        }
    }
}
