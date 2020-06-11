/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

public final class ConfigurationReader {
    private static final java.util.regex.Pattern seperator = java.util.regex.Pattern.compile("[./] *");
    private static final Logger logger = LogManager.getLogger(Configuration.class);

    private ConfigurationReader() {
    }

    /**
     * Merge the given transaction log into the given configuration.
     *
     * @param config  configuration to merge into
     * @param r0 reader of the transaction log to read from
     * @param forceTimestamp forceTimestamp
     * @param mergeCondition mergeCondition
     * @throws IOException if reading fails
     */
    public static void mergeTLogInto(Configuration config,
                                     Reader r0,
                                     boolean forceTimestamp,
                                     Predicate<Topic> mergeCondition) throws IOException {
        try (BufferedReader in = r0 instanceof BufferedReader ? (BufferedReader) r0 : new BufferedReader(r0)) {
            String l;

            for (l = in.readLine(); l != null; l = in.readLine()) {
                try {
                    Tlogline tlogline = Tlogline.fromStringInput(l);
                    Topic targetTopic = config.lookup(seperator.split(tlogline.topicString));
                    if (mergeCondition != null && !mergeCondition.test(targetTopic)) {
                        continue;
                    }
                    if (WhatHappened.changed.equals(tlogline.action)) {
                        targetTopic.withNewerValue(tlogline.timestamp, tlogline.value, forceTimestamp);
                    } else if (WhatHappened.removed.equals(tlogline.action)) {
                        if (forceTimestamp) {
                            targetTopic.remove();
                        } else {
                            targetTopic.remove(tlogline.timestamp);
                        }
                    }
                } catch (Tlogline.InvalidLogException e) {
                    logger.atError().setCause(e).log("Fail to parse log line");
                }
            }

            // block until all changes are merged in
            config.context.runOnPublishQueueAndWait(() -> {});
        }
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

    /**
     * Merge the given transaction log into the given configuration.
     *
     * @param config         configuration to merge into
     * @param tlogPath       path of the tlog file to read to-be-merged config from
     * @param forceTimestamp should ignore if the proposed timestamp is older than current
     * @param mergeCondition Predicate that returns true if the provided Topic should be merged and false if not
     *
     * @throws IOException if reading fails
     */
    public static void mergeTlogIntoConfig(Configuration config, Path tlogPath, boolean forceTimestamp,
                                           Predicate<Topic> mergeCondition)
            throws IOException {
        mergeTLogInto(config, Files.newBufferedReader(tlogPath), forceTimestamp, mergeCondition);
    }
}
