/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

import java.time.Instant;

/**
 * Represents a docker registry derived from a component artifact specification.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Registry {

    @EqualsAndHashCode.Include
    private String endpoint;
    private RegistryType type;
    private RegistrySource source;
    @Setter
    private Credentials credentials;

    /**
     * Constructor.
     *
     * @param endpoint Registry endpoint
     * @param registrySource Source of the registry, i.e. hosted in ECR or other registry servers
     * @param registryType Type of the registry, i.e. private or public
     */
    public Registry(String endpoint, RegistrySource registrySource, RegistryType registryType) {
        this.endpoint = endpoint;
        this.type = registryType;
        this.source = registrySource;
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
        return RegistrySource.ECR.equals(source);
    }

    /**
     * Check if the image is private.
     *
     * @return evaluation result
     */
    public boolean isPrivateRegistry() {
        return RegistryType.PRIVATE.equals(type);
    }

    public enum RegistryType {
        PRIVATE, PUBLIC
    }

    public enum RegistrySource {
        ECR, OTHER
    }

    /**
     * Registry credentials.
     */
    @Getter
    public static class Credentials {
        @NonNull
        private String username;
        @NonNull
        private String password;
        // For ECR, credential validity is 12 hrs, for other registries, credentials should be static
        private Instant expiresAt;

        /**
         * Constructor.
         *
         * @param username username
         * @param password password
         */
        public Credentials(String username, String password) {
            this(username, password, Instant.MAX);
        }

        /**
         * Constructor.
         *
         * @param username username
         * @param password password
         * @param expiresAt time when credential expires
         */
        public Credentials(String username, String password, Instant expiresAt) {
            this.username = username;
            this.password = password;
            this.expiresAt = expiresAt;
        }

        /**
         * Check if credentials are valid at any point in time, if not they need to be refreshed.
         *
         * @return validity
         */
        public boolean isValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
