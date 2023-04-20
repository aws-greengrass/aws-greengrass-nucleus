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
    private static final TypeReference<Tlogline> TLOG_LINE_REF = new TypeReference<Tlogline>() {
    };

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
                    Tlogline tlogline = Coerce.toObject(l, TLOG_LINE_REF);
                    if (WhatHappened.changed.equals(tlogline.action)) {

                        Topic targetTopic = config.lookup(tlogline.timestamp, tlogline.topicPath);
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
                    } else if (WhatHappened.timestampUpdated.equals(tlogline.action)) {
                        Topic targetTopic = config.lookup(tlogline.topicPath);
                        if (tlogline.timestamp > targetTopic.modtime) {
                            targetTopic.modtime = tlogline.timestamp;
                        }
                    } else if (WhatHappened.interiorAdded.equals(tlogline.action)) {
                        config.lookupTopics(tlogline.timestamp, tlogline.topicPath);
                    }
                } catch (JsonProcessingException e) {
                    // this should not happen since all tlog lines were validated previously
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
        try (BufferedReader bufferedReader = Files.newBufferedReader(tlogPath)) {
            mergeTLogInto(config, bufferedReader, forceTimestamp, mergeCondition);
        }
    }

    private static void mergeTLogInto(Configuration c, Path p) throws IOException {
        try (BufferedReader bufferedReader = Files.newBufferedReader(p)) {
            mergeTLogInto(c, bufferedReader, false, null);
        }
    }

    /**
     * Validate the tlog contents at the given path.
     *
     * @param tlogPath path to the file to validate.
     * @return true if all entries in the file are valid;
     *         false if file doesn't exist, is empty, or contains invalid entry
     */
    public static boolean validateTlog(Path tlogPath) {
        try {
            if (!Files.exists(tlogPath)) {
                logger.atDebug().setEventType("validate-tlog").kv("path", tlogPath)
                        .log("Transaction log file does not exist at given path");
                return false;
            }
            try (BufferedReader in = Files.newBufferedReader(tlogPath)) {
                // We have seen two different file corruption scenarios
                // 1. The last line of config file is corrupted with non-UTF8 characters and BufferedReader::readLine
                // throws a MalformedInputException.
                // 2. The config file is filled with kilobytes of null bytes and the first line read from file is not
                // parseable.
                // To handle both scenarios and make sure we can fall back to backup config files, we decided to
                // validate the entire file.

                // Specific description of scenario 2:
                // We have been seeing that very rarely the transaction log gets corrupted when a device (specifically
                // raspberry pi using an SD card) has a power outage.
                // The corruption is happening at the hardware level and there really isn't anything that we can do
                // about it right now.
                // The corruption that we see is that the tlog file is filled with kilobytes of null
                // bytes, depending on how large the configuration was before dumping the entire config to disk.

                String l = in.readLine();
                // if file is empty, return false
                if (l == null) {
                    logger.atError().setEventType("validate-tlog").kv("path", tlogPath)
                            .log("Empty transaction log file");
                    return false;
                }

                // if file is not empty, validate that the entire file is parseable
                while (l != null) {
                    Coerce.toObject(l, TLOG_LINE_REF);
                    l = in.readLine();
                }
            }
        } catch (IOException e) {
            logger.atError().setCause(e).setEventType("validate-tlog").kv("path", tlogPath)
                    .log("Unable to validate the transaction log content");
            return false;
        }
        return true;
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
