package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.util.Exec;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlatformResolver {

    private static final Set<String> SUPPORTED_PLATFORMS = new HashSet<>();
    private static final HashMap<String, Integer> RANKS = new HashMap<>();

    static {
        initialize();
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "DMI_HARDCODED_ABSOLUTE_FILENAME")
    private static void initialize() {
        // figure out what OS we're running and add applicable tags
        // The more specific a tag is, the higher its rank should be
        // TODO: use better way to determine if a field is platform specific. Eg: using 'platform$' prefix.
        RANKS.put("all", 0);
        RANKS.put("any", 0);
        if (Files.exists(Paths.get("/bin/bash")) || Files.exists(Paths.get("/usr/bin/bash"))) {
            RANKS.put("unix", 3);
            RANKS.put("posix", 3);
        }
        if (Files.exists(Paths.get("/proc"))) {
            RANKS.put("linux", 10);
        }
        if (Files.exists(Paths.get("/usr/bin/apt-get"))) {
            RANKS.put("debian", 11);
        }
        if (Exec.isWindows) {
            RANKS.put("windows", 5);
        }
        if (Files.exists(Paths.get("/usr/bin/yum"))) {
            RANKS.put("fedora", 11);
        }
        String sysver = Exec.sh("uname -a").toLowerCase();
        if (sysver.contains("ubuntu")) {
            RANKS.put("ubuntu", 20);
        }
        if (sysver.contains("darwin")) {
            RANKS.put("macos", 20);
        }
        if (sysver.contains("raspbian")) {
            RANKS.put("raspbian", 22);
        }
        if (sysver.contains("qnx")) {
            RANKS.put("qnx", 22);
        }
        if (sysver.contains("cygwin")) {
            RANKS.put("cygwin", 22);
        }
        if (sysver.contains("freebsd")) {
            RANKS.put("freebsd", 22);
        }
        if (sysver.contains("solaris") || sysver.contains("sunos")) {
            RANKS.put("solaris", 22);
        }
        try {
            RANKS.put(InetAddress.getLocalHost().getHostName(), 99);
        } catch (UnknownHostException ex) {
        }

        SUPPORTED_PLATFORMS.addAll(Arrays.asList("all", "any", "unix", "posix", "linux", "debian", "windows", "fedora",
                "ubuntu", "macos", "raspbian", "qnx", "cygwin", "freebsd", "solaris", "sunos"));
    }

    public static Object resolvePlatform(Map<Object, Object> input) {
        return resolvePlatform(RANKS, input);
    }

    private static Object resolvePlatform(Map<String, Integer> ranks, Map<Object, Object> input) {
        int bestrank = -1;
        boolean platformResolved = false;
        Object bestRankNode = null;

        // assuming that either All or None of the children nodes are platform specific fields.
        for (Map.Entry<Object, Object> entry : input.entrySet()) {
            if (!SUPPORTED_PLATFORMS.contains(entry.getKey())) {
                continue;
            }
            platformResolved = true;
            Integer g = ranks.get(entry.getKey());
            if (g != null && g > bestrank) {
                bestrank = g;
                bestRankNode = entry.getValue();
            }
        }
        if (!platformResolved) {
            Map<Object, Object> outputMap = new HashMap<>();
            for (Map.Entry<Object, Object> entry : input.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    Object resolvedValue = resolvePlatform(ranks, (Map<Object, Object>) entry.getValue());
                    if (resolvedValue != null) {
                        outputMap.put(entry.getKey(), resolvedValue);
                    }
                } else {
                    outputMap.put(entry.getKey(), entry.getValue());
                }
            }
            return outputMap;
        } else {
            // assume no nested platform specific configurations.
            return bestRankNode;

            // if nested platform specific node is allowed, use below code. Can have validation on the ranks so that inner node rank can't exceed outer node rank.
            /*
            if (bestRankNode == null) {
                return null;
            }
            if (bestRankNode instanceof Map) {
                return resolvePlatform((Map<Object, Object>) bestRankNode);
            }
            return bestRankNode;
            */
        }
    }
}
