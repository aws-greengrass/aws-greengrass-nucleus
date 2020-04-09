/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.iot.evergreen.util.Coerce.toObject;
import static com.aws.iot.evergreen.util.Utils.parseLong;

public final class ConfigurationReader {
    private static final java.util.regex.Pattern logLine =
            java.util.regex.Pattern.compile("([0-9]+),([^,]*),([^\n]*)\n*");
    private static final java.util.regex.Pattern seperator = java.util.regex.Pattern.compile("[./] *");

    private ConfigurationReader() {
    }

    /**
     * Merge the given transaction log into the given configuration.
     *
     * @param c  configuration to merge into
     * @param r0 reader of the transaction log to read from
     * @throws IOException if reading fails
     */
    public static void mergeTLogInto(Configuration c, Reader r0) throws IOException {
        try (BufferedReader in = r0 instanceof BufferedReader ? (BufferedReader) r0 : new BufferedReader(r0)) {
            String l = in.readLine();
            while (l != null) {
                java.util.regex.Matcher m = logLine.matcher(l);
                if (m.matches()) {
                    c.lookup(seperator.split(m.group(2))).withValue(parseLong(m.group(1)), toObject(m.group(3)));
                }
                l = in.readLine();
            }
        }
    }

    public static void mergeTLogInto(Configuration c, Path p) throws IOException {
        ConfigurationReader.mergeTLogInto(c, Files.newBufferedReader(p));
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
