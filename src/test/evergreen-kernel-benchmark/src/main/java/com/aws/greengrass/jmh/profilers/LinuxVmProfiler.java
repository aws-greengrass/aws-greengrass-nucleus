/*
 * #%L
 * Benchmarks: JMH suite.
 * %%
 * Copyright (C) 2013 - 2019 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 *
 * Modifications copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.jmh.profilers;

import org.openjdk.jmh.results.AggregationPolicy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Add Linux VM metrics to the result.
 *
 * @author Jens Wilke
 */
public class LinuxVmProfiler {
    /**
     * Parse the linux {@code /proc/self/status} and add everything prefixed with "Vm" as metric to
     * the profiling result.
     */
    public static void addLinuxVmStats(String prefix, List<OptionalScalarResult> l) {
        // procfs is not available on mac or windows, so just skip these stats
        if (!new File("/proc/self/status").exists()) {
            return;
        }
        try {
            LineNumberReader r = new LineNumberReader(new InputStreamReader(new FileInputStream("/proc/self/status")));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("Vm")) {
                    continue;
                }
                String[] sa = line.split("\\s+");
                if (sa.length != 3) {
                    throw new IOException("Format error: 3 elements expected");
                }
                if (!sa[2].equals("kB")) {
                    throw new IOException("Format error: unit kB expected, was: " + sa[2]);
                }
                String name = sa[0].substring(0, sa[0].length() - 1);
                // Vm data is measured in KB, so convert it to bytes to match our other measurements
                l.add(new OptionalScalarResult(prefix + "." + name, (double) Long.parseLong(sa[1]) * 1024, "bytes",
                        AggregationPolicy.AVG));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
