/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.fasterxml.jackson.jr.ob.JSON;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.inject.Inject;

import static com.aws.iot.evergreen.util.Utils.close;
import static com.aws.iot.evergreen.util.Utils.extension;

public class Configuration {
    public static final Object removed = new Object() {
        @Override
        public String toString() {
            return "removed";
        }
    };
    private static final java.util.regex.Pattern seperator = java.util.regex.Pattern.compile("[./] *");
    public final Context context;
    final Topics root;
    private static final Logger logger = LogManager.getLogger(Configuration.class);

    @Inject
    @SuppressWarnings("LeakingThisInConstructor")
    public Configuration(Context c) {  // This is one of the few classes that can't use injection
        c.put(Configuration.class, this);
        root = new Topics(context = c, null, null);
    }

    public static String[] splitPath(String path) {
        return seperator.split(path);
    }

    /**
     * Find, and create if missing, a topic (a name/value pair) in the config
     * file. Never returns null.
     *
     * @param path String[] of node names to traverse to find or create the Topic
     */
    public Topic lookup(String... path) {
        int limit = path.length - 1;
        Topics n = root;
        for (int i = 0; i < limit; i++) {
            n = n.createInteriorChild(path[i]);
        }
        return n.createLeafChild(path[limit]);
    }

    /**
     * Find, and create if missing, a list of topics (name/value pairs) in the
     * config file. Never returns null.
     *
     * @param path String[] of node names to traverse to find or create the Topics
     */
    public Topics lookupTopics(String... path) {
        Topics n = root;
        for (String s : path) {
            n = n.createInteriorChild(s);
        }
        return n;
    }

    /**
     * Find, but do not create if missing, a topic (a name/value pair) in the
     * config file. Returns null if missing.
     *
     * @param path String[] of node names to traverse to find the Topic
     */
    public Topic find(String... path) {
        int limit = path.length - 1;
        Topics n = root;
        for (int i = 0; i < limit && n != null; i++) {
            n = n.findInteriorChild(path[i]);
        }
        return n == null ? null : n.findLeafChild(path[limit]);
    }

    /**
     * Find, but do not create if missing, a topic (a name/value pair) in the
     * config file. Returns null if missing.
     *
     * @param path String[] of node names to traverse to find the Topics
     */
    public Topics findTopics(String... path) {
        int limit = path.length;
        Topics n = root;
        for (int i = 0; i < limit && n != null; i++) {
            n = n.findInteriorChild(path[i]);
        }
        return n;
    }

    public Topics getRoot() {
        return root;
    }

    public boolean isEmpty() {
        return root == null || root.isEmpty();
    }

    public int size() {
        return root == null ? 0 : root.size();
    }

    /**
     * Merges a Map into this configuration. The merge will resolve platform. The most common use case is for
     * reading textual config files via jackson-jr. For example, to merge a
     * .yaml file:
     * <br><code>
     * config.mergeMap(<b>timestamp</b>, (Map)JSON.std.with(new
     * YAMLFactory()).anyFrom(<b>inputStream</b>));
     * </code><br>
     * If you omit the <code>.with(...)</code> clause, you get the default
     * parser, which is JSON. You can replace <code>new YAMLFactory()</code>
     * with any other supported parser.
     *
     * @param timestamp last modified time for the configuration values
     * @param map       map to merge
     * @throws IllegalArgumentException Should not be possible
     */
    public void mergeMap(long timestamp, Map<Object, Object> map) throws IllegalArgumentException {
        Object resolvedPlatformMap = PlatformResolver.resolvePlatform(map);
        if (!(resolvedPlatformMap instanceof Map)) {
            throw new IllegalArgumentException("Invalid config after resolving platform: " + resolvedPlatformMap);
        }
        root.mergeMap(timestamp, (Map<Object, Object>) resolvedPlatformMap);
    }

    public Map<String, Object> toPOJO() {
        return root.toPOJO();
    }

    public void deepForEachTopic(Consumer<Topic> f) {
        root.deepForEachTopic(f);
    }

    public Configuration read(String s) throws IOException {
        return s.contains(":/") ? read(new URL(s), false) : read(Paths.get(s));
    }

    /**
     * Read and merge configuration from a URL.
     *
     * @param url                configuration source URL
     * @param useSourceTimestamp true if the modified time should be set based on the value from the server (if any)
     * @return this with the new configuration merged in
     * @throws IOException if the reading fails
     */
    public Configuration read(URL url, boolean useSourceTimestamp) throws IOException {
        logger.atInfo().addKeyValue("url", url).setEventType("config-loading").log("Read configuration from a URL");
        URLConnection u = url.openConnection();
        return read(u.getInputStream(), extension(url.getPath()),
                useSourceTimestamp ? u.getLastModified() : System.currentTimeMillis());
    }

    /**
     * Read a new Configuration from a given Path.
     *
     * @param s path to read the config from.
     * @return configuration from the path.
     * @throws IOException if the reading fails.
     */
    public Configuration read(Path s) throws IOException {
        logger.atInfo().addKeyValue("path", s).setEventType("config-loading")
                .log("Read configuration from a file path");
        return read(Files.newBufferedReader(s), extension(s.toString()), Files.getLastModifiedTime(s).toMillis());
    }

    public Configuration read(InputStream in, String extension, long timestamp) throws IOException {
        return read(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), extension, timestamp);
    }

    /**
     * Read in a configuration from a Reader and merge it with the current configuration.
     *
     * @param in        reader to read new configuration from
     * @param extension extension of the file we're reading in (changes how we deserialize the input data)
     * @param timestamp timestamp to use as the last modified time
     * @return this with the merged in configuration
     * @throws IOException              if reading fails
     * @throws IllegalArgumentException if the file extension is not supported
     */
    public Configuration read(Reader in, String extension, long timestamp)
            throws IOException, IllegalArgumentException {
        try {
            switch (extension) {
                case "json":
                    mergeMap(timestamp, (java.util.Map) JSON.std.anyFrom(in));
                    break;
                case "evg":  // evergreen
                case "yaml":
                    mergeMap(timestamp,
                            (java.util.Map) JSON.std.with(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                                    .anyFrom(in));
                    break;
                case "tlog":
                    ConfigurationReader.mergeTLogInto(this, in);
                    break;
                default:
                    throw new IllegalArgumentException(
                            "File format '" + extension + "' is not supported.  Use one of: yaml, json or tlog");
            }
        } finally {
            close(in);
        }
        return this;
    }

    /**
     * Read in a new configuration from a URL and merge it into the current config.
     *
     * @param u               URL to read in the configuration from
     * @param sourceTimestamp true if the URL source timestamp should be used as the last modified time
     * @return any throwable that occurs from the merge or read
     */
    public Throwable readMerge(URL u, boolean sourceTimestamp) {
        // TODO: Does not handle dependencies properly yet
        // TODO: Nor are environment variables accounted for properly
        /* We run the operation on the publish queue to ensure that no listeners are
         * fired while the large config change is happening.  They get reconciled
         * all together */
        return context.runOnPublishQueueAndWait(() -> {
            logger.atDebug().setEventType("config-merge-start").addKeyValue("url", u)
                    .log("Start merging configuration");
            read(u, sourceTimestamp);
            logger.atDebug().setEventType("config-merge-finish").addKeyValue("url", u)
                    .log("Finish merging configuration");
        });
    }

    public Configuration copyFrom(Configuration other) {
        getRoot().copyFrom(other.getRoot());
        return this;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(root);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Configuration && root.equals(((Configuration) o).root);
    }
}
