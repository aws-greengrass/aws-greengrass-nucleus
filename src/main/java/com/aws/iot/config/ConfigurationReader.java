/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.config;

import static com.aws.iot.util.Coerce.*;
import static com.aws.iot.util.Utils.*;
import java.io.*;
import java.nio.file.*;

public class ConfigurationReader {
    public static void mergeTLogInto(Configuration c, Reader r0) throws IOException {
        try (BufferedReader in = r0 instanceof BufferedReader ? (BufferedReader) r0 : new BufferedReader(r0)) {
            String l;
            while ((l = in.readLine()) != null) {
                java.util.regex.Matcher m = logLine.matcher(l);
                if (m.matches()) 
                c.lookup(seperator.split(m.group(2))).setValue(
                        parseLong(m.group(1)), toObject(m.group(3)));
            }
        }
    }
    public static void mergeTLogInto(Configuration c, Path p) throws IOException {
        ConfigurationReader.mergeTLogInto(c, Files.newBufferedReader(p));
    }
    public static Configuration createFromTLog(Path p) throws IOException {
        Configuration c = new Configuration();
        ConfigurationReader.mergeTLogInto(c, p);
        return c;
    }
    private static final java.util.regex.Pattern logLine = java.util.regex.Pattern.compile("([0-9]+),([^,]*),([^\n]*)\n*");
    private static final java.util.regex.Pattern seperator = java.util.regex.Pattern.compile("[./] *");
}
