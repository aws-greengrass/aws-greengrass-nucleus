/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.kernel.EvergreenService;
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
import java.util.Arrays;
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
     */
    public Topics findTopics(String... path) {
        int limit = path.length;
        Topics n = root;
        for (int i = 0; i < limit && n != null; i++) {
            n = n.findInteriorChild(path[i]);
        }
        return n;
    }

    /**
     * Find Topic by path after resolving the path for the OS.
     * Allows us to find Service.Lookup, or Service.Linux.Lookup whichever is appropriate.
     */
    public Topic findResolvedTopic(String... path) {
        Topics t = findTopics(Arrays.copyOfRange(path, 0, path.length - 1));
        if (t == null) {
            return null;
        }
        Node topics = EvergreenService.pickByOS(t);
        if (topics != null) {
            if (topics instanceof Topics) {
                return ((Topics) topics).findLeafChild(path[path.length - 1]);
            } else if (topics instanceof Topic) {
                return (Topic) topics;
            }
        }
        return find(path);
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
     * Merges a Map into this configuration. The most common use case is for
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
     * @param timestamp
     * @param map
     */
    public void mergeMap(long timestamp, Map<Object, Object> map) {
        root.mergeMap(timestamp, map);
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

    public Configuration read(URL url, boolean useSourceTimestamp) throws IOException {
        context.getLog().significant("Reading URL", url);
        URLConnection u = url.openConnection();
        return read(u.getInputStream(), extension(url.getPath()), useSourceTimestamp ? u.getLastModified() :
                System.currentTimeMillis());
    }

    public Configuration read(Path s) throws IOException {
        context.getLog().significant("Reading", s);
        return read(Files.newBufferedReader(s), extension(s.toString()), Files.getLastModifiedTime(s).toMillis());
    }

    public Configuration read(InputStream in, String extension, long timestamp) throws IOException {
        return read(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), extension, timestamp);
    }

    public Configuration copyFrom(Configuration other) {
        getRoot().copyFrom(other.getRoot());
        return this;
    }

    public Configuration read(Reader in, String extension, long timestamp) throws IOException {
        try {
            switch (extension) {
                case "json":
                    mergeMap(timestamp, (java.util.Map) JSON.std.anyFrom(in));
                    break;
                case "evg":  // evergreen
                case "yaml":
                    mergeMap(timestamp,
                            (java.util.Map) JSON.std.with(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).anyFrom(in));
                    break;
                case "tlog":
                    ConfigurationReader.mergeTLogInto(this, in);
                    break;
                default:
                    throw new IllegalArgumentException("File format '" + extension
                            + "' is not supported.  Use one of: yaml, json or tlog");
            }
        } finally {
            close(in);
        }
        return this;
    }

    public Throwable readMerge(URL u, boolean sourceTimestamp) {
        // TODO: Does not handle dependencies properly yet
        // TODO: Nor are environment variables accounted for properly
        /* We run the operation on the publish queue to ensure that no listeners are
         * fired while the large config change is happening.  They get reconciled
         * all together */
        return context.runOnPublishQueueAndWait(() -> {
            context.getLog().note("Merging " + u);
            read(u, sourceTimestamp);
            context.getLog().note("Finished " + u);
        });
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
