/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.config;

import static com.aws.iot.util.Utils.*;
import com.fasterxml.jackson.jr.ob.JSON;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

public class Configuration {
    private final Topics root = new Topics(null, null);

    public enum WhatHappened {
        changed, initialized, childChanged, removed, childRemoved
    };
    /**
     * Find, and create if missing, a topic (a name/value pair) in the config
     * file. Never returns null.
     */
    public Topic lookup(String... path) {
        int limit = path.length - 1;
        Topics n = root;
        for (int i = 0; i < limit; i++)
            n = n.createInteriorChild(path[i]);
        return n.createLeafChild(path[limit]);
    }
    /**
     * Find, and create if missing, a list of topics (name/value pairs) in the
     * config file. Never returns null.
     */
    public Topics lookupTopics(String... path) {
        int limit = path.length;
        Topics n = root;
        for (int i = 0; i < limit; i++)
            n = n.createInteriorChild(path[i]);
        return n;
    }
    /**
     * Find, but do not create if missing, a topic (a name/value pair) in the
     * config file. Returns null if missing.
     */
    public Topic find(String... path) {
        int limit = path.length - 1;
        Topics n = root;
        for (int i = 0; i < limit && n != null; i++)
            n = n.findInteriorChild(path[i]);
        return n == null ? null : n.findLeafChild(path[limit]);
    }
    /**
     * Find, but do not create if missing, a topic (a name/value pair) in the
     * config file. Returns null if missing.
     */
    public Topics findTopics(String... path) {
        int limit = path.length;
        Topics n = root;
        for (int i = 0; i < limit && n != null; i++)
            n = n.findInteriorChild(path[i]);
        return n;
    }
    public Topics getRoot() {
        return root;
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
    public Map<String,Object> toPOJO() { return root.toPOJO(); }
    public void deepForEachTopic(Consumer<Topic> f) {
        root.deepForEachTopic(f);
    }
    public Configuration read(String s) throws IOException {
        System.out.println("Reading " + s);
        if(s.contains(":/")) {
            URLConnection u = new URL(s).openConnection();
            return read(u.getInputStream(), extension(s), u.getLastModified());
        } else return read(Paths.get(s));
    }
    public Configuration read(Path s) throws IOException {
        System.out.println("Reading " + s);
        return read(Files.newBufferedReader(s), extension(s.toString()),
                Files.getLastModifiedTime(s).toMillis());
    }
    public Configuration read(InputStream in, String extension, long timestamp) throws IOException {
        return read(new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8"))),
                extension, timestamp);
    }
    public Configuration read(Reader in, String extension, long timestamp) throws IOException {
        try {
            switch (extension) {
                case "json":
                    mergeMap(0, (java.util.Map) JSON.std.anyFrom(in));
                    break;
                case "yaml":
                    mergeMap(0, (java.util.Map) JSON.std.with(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).anyFrom(in));
                    break;
                case "tlog":
                    ConfigurationReader.mergeTLogInto(this, in);
                    break;
                default:
                    throw new IllegalArgumentException("File format '" + extension + "' is not supported.  Use one of: yaml, json or tlog");
            }
        } finally {
            close(in);
        }
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
    public static String[] splitPath(String path) {
        return seperator.split(path);
    }
    
    private static final java.util.regex.Pattern seperator = java.util.regex.Pattern.compile("[./] *");

    public static final Object removed = new Object() {
        @Override
        public String toString() {
            return "removed";
        }
    };
}
