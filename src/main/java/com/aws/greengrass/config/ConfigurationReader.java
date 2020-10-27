/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

public final class ConfigurationReader {
    private static final Logger logger = LogManager.getLogger(ConfigurationReader.class);

    private ConfigurationReader() {
    }

    /**
     * Merge the given transaction log into the given configuration.
     *
     * @param config         configuration to merge into
     * @param reader         reader of the transaction log to read from
     * @param forceTimestamp should ignore if the proposed timestamp is older than current
     * @param mergeCondition Predicate that returns true if the provided Topic should be merged and false if not
     * @throws IOException if reading fails
     */
    public static void mergeTLogInto(Configuration config, Reader reader, boolean forceTimestamp,
                                     Predicate<Node> mergeCondition) throws IOException {
        try (BufferedReader in = reader instanceof BufferedReader ? (BufferedReader) reader
                : new BufferedReader(reader)) {
            String l;

            for (l = in.readLine(); l != null; l = in.readLine()) {
                try {
                    Tlogline tlogline = Coerce.toObject(l, new TypeReference<Tlogline>() {});
                    if (WhatHappened.changed.equals(tlogline.action)) {
                        Topic targetTopic = config.lookup(tlogline.topicPath);
                        if (mergeCondition != null && !mergeCondition.test(targetTopic)) {
                            continue;
                        }
                        targetTopic.withNewerValue(tlogline.timestamp, tlogline.value, forceTimestamp);
                    } else if (WhatHappened.removed.equals(tlogline.action)) {
                        Node n = config.findNode(tlogline.topicPath);
                        if (n == null) {
                            continue;
                        }
                        if (mergeCondition != null && !mergeCondition.test(n)) {
                            continue;
                        }
                        if (forceTimestamp) {
                            n.remove();
                        } else {
                            n.remove(tlogline.timestamp);
                        }
                    }

                } catch (JsonProcessingException e) {
                    logger.atError().setCause(e).log("Fail to parse log line");
                }
            }
        }
    }

    /**
     * Merge the given transaction log into the given configuration.
     *
     * @param config         configuration to merge into
     * @param tlogPath       path of the tlog file to read to-be-merged config from
     * @param forceTimestamp should ignore if the proposed timestamp is older than current
     * @param mergeCondition Predicate that returns true if the provided Topic should be merged and false if not
     * @throws IOException if reading fails
     */
    public static void mergeTLogInto(Configuration config, Path tlogPath, boolean forceTimestamp,
                                     Predicate<Node> mergeCondition) throws IOException {
        mergeTLogInto(config, Files.newBufferedReader(tlogPath), forceTimestamp, mergeCondition);
    }

    private static void mergeTLogInto(Configuration c, Path p) throws IOException {
        mergeTLogInto(c, Files.newBufferedReader(p), false, null);
    }

    /**
     * Create a Configuration based on a transaction log's path.
     *
     * @param context root context for the configuration
     * @param p       path to the transaction log
     * @return Configuration from the transaction log
     * @throws IOException if reading the transaction log fails
     */
    public static Configuration createFromTLog(Context context, Path p) throws IOException {
        Configuration c = new Configuration(context);
        ConfigurationReader.mergeTLogInto(c, p);
        return c;
    }
}
