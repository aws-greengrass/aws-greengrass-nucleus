/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.util;

import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.platforms.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigPlatformResolver {

    public static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("wind");

    private static final Set<String> SUPPORTED_PLATFORMS = Collections.unmodifiableSet(initializeSupportedPlatforms());
    private static final Logger logger = LogManager.getLogger(ConfigPlatformResolver.class);

    public static final AtomicReference<Map<String, Integer>> RANKS =
            new AtomicReference<>(Collections.unmodifiableMap(initializeRanks()));

    private final static ObjectMapper YAML_OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    public static void initKernelWithMultiPlatformConfig(Kernel kernel, URL path) throws IOException {
        Map<String, Object> objectMap = YAML_OBJECT_MAPPER.readValue(path, Map.class);
        kernel.parseArgs();
        kernel.getContext().runOnPublishQueueAndWait(
                () -> kernel.getConfig().mergeMap(System.currentTimeMillis(), resolvePlatformMap(objectMap)));
        kernel.getContext().waitForPublishQueueToClear();
    }

    private static Set<String> initializeSupportedPlatforms() {
        return new HashSet<>(
                Arrays.asList("all", "any", "unix", "posix", "linux", "debian", "windows", "fedora", "ubuntu", "macos",
                        "raspbian", "qnx", "cygwin", "freebsd", "solaris", "sunos"));
    }

    @SuppressFBWarnings("DMI_HARDCODED_ABSOLUTE_FILENAME")
    private static Map<String, Integer> initializeRanks() {
        Map<String, Integer> ranks = new HashMap<>();
        // figure out what OS we're running and add applicable tags
        // The more specific a tag is, the higher its rank should be
        ranks.put("all", 0);
        ranks.put("any", 0);
        if (Files.exists(Paths.get("/bin/sh")) || Files.exists(Paths.get("/bin/bash"))
                || Files.exists(Paths.get("/usr/bin/bash"))) {
            ranks.put("unix", 3);
            ranks.put("posix", 3);
        }
        if (Files.exists(Paths.get("/proc"))) {
            ranks.put("linux", 10);
        }
        if (Files.exists(Paths.get("/usr/bin/apt-get"))) {
            ranks.put("debian", 11);
        }
        if (isWindows) {
            ranks.put("windows", 5);
        }
        if (Files.exists(Paths.get("/usr/bin/yum"))) {
            ranks.put("fedora", 11);
        }
        if (!isWindows) {
            try {
                String sysver = Platform.getInstance().createNewProcessRunner().sh("uname -a").toLowerCase();
                if (sysver.contains("ubuntu")) {
                    ranks.put("ubuntu", 20);
                }
                if (sysver.contains("darwin")) {
                    ranks.put("darwin", 10);
                    ranks.put("macos", 20);
                }
                if (sysver.contains("raspbian")) {
                    ranks.put("raspbian", 22);
                }
                if (sysver.contains("qnx")) {
                    ranks.put("qnx", 22);
                }
                if (sysver.contains("cygwin")) {
                    ranks.put("cygwin", 22);
                }
                if (sysver.contains("freebsd")) {
                    ranks.put("freebsd", 22);
                }
                if (sysver.contains("solaris") || sysver.contains("sunos")) {
                    ranks.put("solaris", 22);
                }
            } catch (InterruptedException | IOException e) {
                logger.atError().log("Error while running uname -a");
            }
        }
        return ranks;
    }

    /**
     * Get the most specific platform string for the current system.
     *
     * @return platform
     */
    public static String getPlatform() {
        return RANKS.get().entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue)).map(Map.Entry::getKey)
                .orElse("all");
    }

    public static Map<String, Object> resolvePlatformMap(URL path) throws IOException {
        Map<String, Object> objectMap = YAML_OBJECT_MAPPER.readValue(path, Map.class);
        return (Map<String, Object>) resolvePlatform(RANKS.get(), objectMap);
    }

    public static Map<String, Object> resolvePlatformMap(Map<String, Object> input) {
        return (Map<String, Object>) resolvePlatform(RANKS.get(), input);
    }

    private static Object resolvePlatform(Map<String, Integer> ranks, Map<String, Object> input) {
        int bestrank = -1;
        boolean platformResolved = false;
        Object bestRankNode = null;

        // assuming that either All or None of the children nodes are platform specific fields.
        // This logic remains for now to not break integration tests, but is deprecated
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (!SUPPORTED_PLATFORMS.contains(entry.getKey())) {
                continue;
            }
            logger.info("platform keyword {} was applied.", entry.getKey());
            platformResolved = true;
            Integer g = ranks.get(entry.getKey());
            if (g != null && g > bestrank) {
                bestrank = g;
                bestRankNode = entry.getValue();
            }
        }
        if (!platformResolved) {
            Map<String, Object> outputMap = new HashMap<>();
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    Object resolvedValue = resolvePlatform(ranks, (Map<String, Object>) entry.getValue());
                    if (resolvedValue != null) {
                        outputMap.put(entry.getKey(), resolvedValue);
                    }
                } else {
                    outputMap.put(entry.getKey(), entry.getValue());
                }
            }
            return outputMap;
        }
        // assume no nested platform specific configurations.
        return bestRankNode;
    }

    private ConfigPlatformResolver() {}
}
