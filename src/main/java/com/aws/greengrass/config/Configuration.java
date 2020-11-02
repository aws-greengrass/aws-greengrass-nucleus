/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.inject.Inject;

import static com.aws.greengrass.util.Utils.extension;

public class Configuration {
    private static final java.util.regex.Pattern SEPARATOR = java.util.regex.Pattern.compile("[./] *");
    public final Context context;
    final Topics root;
    private static final Logger logger = LogManager.getLogger(Configuration.class);

    private final AtomicBoolean configUnderUpdate = new AtomicBoolean(false);
    private final Object configUpdateNotifier = new Object();

    @Inject
    @SuppressWarnings("LeakingThisInConstructor")
    public Configuration(Context c) {  // This is one of the few classes that can't use injection
        root = new Topics(context = c, null, null);
        c.put(Configuration.class, this);
    }

    public static String[] splitPath(String path) {
        return SEPARATOR.split(path);
    }

    /**
     * Find, and create if missing, a topic (a name/value pair) in the config
     * file. Never returns null.
     *
     * @param path String[] of node names to traverse to find or create the Topic
     */
    public Topic lookup(String... path) {
        return root.lookup(path);
    }

    /**
     * Find, and create if missing, a list of topics (name/value pairs) in the
     * config file. Never returns null.
     *
     * @param path String[] of node names to traverse to find or create the Topics
     */
    public Topics lookupTopics(String... path) {
        return root.lookupTopics(path);
    }

    /**
     * Find, but do not create if missing, a topic (a name/value pair) in the
     * config file. Returns null if missing.
     *
     * @param path String[] of node names to traverse to find the Topic
     */
    @Nullable
    public Topic find(String... path) {
        return root.find(path);
    }

    /**
     * Find, but do not create if missing, a topic (a name/value pair) in the
     * config file. Returns null if missing.
     *
     * @param path String[] of node names to traverse to find the Topics
     */
    @Nullable
    public Topics findTopics(String... path) {
        return root.findTopics(path);
    }

    /**
     * Find, but do not create if missing, a node in the
     * config file. Returns null if missing.
     *
     * @param path String[] of node names to traverse to find the Topics
     */
    @Nullable
    public Node findNode(String... path) {
        return root.findNode(path);
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
     */
    public void mergeMap(long timestamp, Map<String, Object> map) {
        this.updateMap(map, new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.MERGE, timestamp));
    }

    /**
     * Merges a Map into this configuration. The merge will resolve platform.
     *
     * @param map           map to merge
     * @param updateBehavior the updateBehavior of each node to be merged in
     */
    public void updateMap(Map<String, Object> map, UpdateBehaviorTree updateBehavior) {
        Object resolvedPlatformMap = PlatformResolver.resolvePlatform(map);
        if (!(resolvedPlatformMap instanceof Map)) {
            throw new IllegalArgumentException("Invalid config after resolving platform: " + resolvedPlatformMap);
        }
        // TODO: avoid sending multiple changed/childChanged event when the entire config is being updated.
        configUnderUpdate.set(true);
        root.updateFromMap((Map<String, Object>) resolvedPlatformMap, updateBehavior);
        context.runOnPublishQueue(() -> {
            synchronized (configUpdateNotifier) {
                configUnderUpdate.set(false);
                configUpdateNotifier.notifyAll();
            }
        });
    }

    /**
     * If configuration is under update when this function is invoked, block until the update is complete.
     * @throws InterruptedException InterruptedException
     */
    public void waitConfigUpdateComplete() throws InterruptedException {
        if (!configUnderUpdate.get()) {
            return;
        }
        logger.atInfo().log("Configuration currently updating, will wait for the update to complete.");
        synchronized (configUpdateNotifier) {
            while (configUnderUpdate.get()) {
                configUpdateNotifier.wait(5000);
            }
        }
        logger.atInfo().log("Config update finished.");
    }

    public Map<String, Object> toPOJO() {
        return root.toPOJO();
    }

    public void deepForEachTopic(Consumer<Topic> f) {
        root.deepForEachTopic(f);
    }

    public void forEachChildlessTopics(Consumer<Topics> f) {
        root.forEachChildlessTopics(f);
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
        try (InputStream is = u.getInputStream()) {
            read(is, extension(url.getPath()), useSourceTimestamp ? u.getLastModified() : System.currentTimeMillis());
        }
        return this;
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
        try (BufferedReader br = Files.newBufferedReader(s)) {
            read(br, extension(s.toString()), Files.getLastModifiedTime(s).toMillis());
        }
        return this;
    }

    private void read(InputStream in, String extension, long timestamp) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            read(br, extension, timestamp);
        }
    }

    /**
     * Read in a configuration from a Reader and merge it with the current configuration.
     *
     * @param in        reader to read new configuration from
     * @param extension extension of the file we're reading in (changes how we deserialize the input data)
     * @param timestamp timestamp to use as the last modified time
     * @throws IOException              if reading fails
     */
    private void read(Reader in, String extension, long timestamp) throws IOException {
        switch (extension) {
            case "json":
                mergeMap(timestamp, (Map) JSON.std.anyFrom(in));
                break;
            case "yaml":
                mergeMap(timestamp,
                        (Map) JSON.std.with(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                                .anyFrom(in));
                break;
            case "tlog":
                ConfigurationReader.mergeTLogInto(this, in, false, null);
                break;
            default:
                throw new IllegalArgumentException(
                        "File format '" + extension + "' is not supported.  Use one of: yaml, json or tlog");
        }
    }

    /**
     * Read in a new configuration from a URL and merge it into the current config.
     *
     * @param u               URL to read in the configuration from
     * @param sourceTimestamp true if the URL source timestamp should be used as the last modified time
     * @return any throwable that occurs from the merge or read
     */
    public Throwable readMerge(URL u, boolean sourceTimestamp) {
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
        return copyFrom(other.getRoot());
    }

    public Configuration copyFrom(Topics other) {
        getRoot().copyFrom(other);
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
