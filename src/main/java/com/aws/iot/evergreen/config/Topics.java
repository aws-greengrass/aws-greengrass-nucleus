/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

public class Topics extends Node implements Iterable<Node> {
    public final ConcurrentHashMap<String, Node> children = new ConcurrentHashMap<>();

    private static final Logger logger = LogManager.getLogger(Topics.class);

    Topics(Context c, String n, Topics p) {
        super(c, n, p);
    }

    public static Topics of(Context c, String n, Topics p) {
        return new Topics(c, n, p);
    }

    /**
     * Create an errorNode with a given message.
     *
     * @param context context
     * @param name    name of the topics node
     * @param message error message
     * @return node
     */
    public static Topics errorNode(Context context, String name, String message) {
        Topics t = new Topics(context, name, null);
        t.createLeafChild("error").withNewerValue(0, message);
        return t;
    }

    @Override
    public void appendTo(Appendable a) throws IOException {
        appendNameTo(a);
        a.append(':');
        a.append(String.valueOf(children));
    }

    public int size() {
        return children.size();
    }

    @Override
    public void copyFrom(Node from) {
        Objects.requireNonNull(from);
        if (from instanceof Topics) {
            ((Topics) from).forEach(n -> {
                Objects.requireNonNull(n);
                if (n instanceof Topic) {
                    createLeafChild(n.getName()).copyFrom(n);
                } else {
                    createInteriorChild(n.getName()).copyFrom(n);
                }
            });
        } else {
            throw new IllegalArgumentException(
                    "copyFrom: " + from.getFullName() + " is already a leaf, not a container");
        }
    }

    public Node getChild(String name) {
        return children.get(name);
    }

    /**
     * Create a leaf Topic under this Topics with the given name.
     * Returns the leaf topic if it already existed.
     *
     * @param name name of the leaf node
     * @return the node
     */
    public Topic createLeafChild(String name) {
        Node n = children.computeIfAbsent(name, (nm) -> new Topic(context, nm, this));
        if (n instanceof Topic) {
            return (Topic) n;
        } else {
            throw new IllegalArgumentException(name + " in " + this + " is already a container, cannot become a leaf");
        }
    }

    /**
     * Create an interior Topics node with the provided name.
     * Returns the new node or the existing node if it already existed.
     *
     * @param name name for the new node
     * @return the node
     */
    public Topics createInteriorChild(String name) {
        Node n = children.computeIfAbsent(name, (nm) -> new Topics(context, nm, this));
        if (n instanceof Topics) {
            return (Topics) n;
        } else {
            throw new IllegalArgumentException(name + " in " + this + " is already a leaf, cannot become a container");
        }
    }

    public Topics findInteriorChild(String name) {
        Node n = getChild(name);
        return n instanceof Topics ? (Topics) n : null;
    }

    public Topic findLeafChild(String name) {
        Node n = getChild(name);
        return n instanceof Topic ? (Topic) n : null;
    }

    /**
     * Find, and create if missing, a topic (a name/value pair) in the config
     * file. Never returns null.
     *
     * @param path String[] of node names to traverse to find or create the Topic
     */
    public Topic lookup(String... path) {
        int limit = path.length - 1;
        Topics n = this;
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
        Topics n = this;
        for (String s : path) {
            n = n.createInteriorChild(s);
        }
        return n;
    }

    /**
     * Find, but do not create if missing, a topic (a name/value pair) in the
     * config file. Returns null if missing.
     *
     * @param path String[] of node names to traverse to find or create the Topic
     */
    public Topic find(String... path) {
        int limit = path.length - 1;
        Topics n = this;
        for (int i = 0; i < limit && n != null; i++) {
            n = n.findInteriorChild(path[i]);
        }
        return n == null ? null : n.findLeafChild(path[limit]);
    }

    /**
     * Find, but do not create if missing, a topic (a name/value pair) in the
     * config file. If the topic exists, it returns the value. If the topic does not
     * exist, then it will return the default value provided.
     *
     * @param defaultV default value if the Topic was not found
     * @param path     String[] of node names to traverse to find or create the Topic
     */
    public Object findOrDefault(Object defaultV, String... path) {
        Topic potentialTopic = find(path);
        if (potentialTopic == null) {
            return defaultV;
        }
        return potentialTopic.getOnce();
    }

    /**
     * Find, but do not create if missing, a topics in the config file. Returns null if missing.
     *
     * @param path String[] of node names to traverse to find or create the Topic
     */
    public Topics findTopics(String... path) {
        int limit = path.length;
        Topics n = this;
        for (int i = 0; i < limit && n != null; i++) {
            n = n.findInteriorChild(path[i]);
        }
        return n;
    }

    protected Node findNode(String... path) {
        int limit = path.length - 1;
        Topics n = this;
        for (int i = 0; i < limit && n != null; i++) {
            n = n.findInteriorChild(path[i]);
        }
        return n == null ? null : n.getChild(path[limit]);
    }

    /**
     * Add the given map to this Topics tree.
     *
     * @param lastModified last modified time
     * @param map          map to merge in
     */
    public void mergeMap(long lastModified, Map<Object, Object> map) {
        updateFromMap(lastModified, map, MergeBehaviorTree.MERGE_ALL);
    }

    /**
     * Replace the given map to this Topics tree.
     *
     * @param lastModified last modified time
     * @param map          map to merge in
     */
    void replaceMap(long lastModified, Map<Object, Object> map) {
        updateFromMap(lastModified, map, MergeBehaviorTree.REPLACE_ALL);
    }

    /**
     * Add the given map to this Topics tree.
     *
     * @param lastModified  last modified time
     * @param map           map to merge in
     * @param mergeBehavior mergeBehavior
     */
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH")
    public void updateFromMap(long lastModified, Map<Object, Object> map, @NonNull MergeBehaviorTree mergeBehavior) {
        if (map == null) {
            logger.atInfo().kv("node", getFullName()).log("Null map received in updateFromMap(), ignoring.");
            return;
        }
        Set<String> childToRemove = new HashSet<>(children.keySet());

        map.forEach((okey, value) -> {
            String key = okey.toString();
            childToRemove.remove(key);
            updateChild(lastModified, key, value, mergeBehavior);
        });

        childToRemove.forEach(child -> {
            MergeBehaviorTree childMergeBehavior = mergeBehavior.getChildOverride().get(child);
            if (childMergeBehavior == null) {
                childMergeBehavior = mergeBehavior.getChildOverride().get(MergeBehaviorTree.WILDCARD);
            }

            if (childMergeBehavior == null
                    && mergeBehavior.getDefaultBehavior() == MergeBehaviorTree.MergeBehavior.REPLACE) {
                remove(children.get(child));
                return;
            }

            // remove the existing child only if its merge behavior is not present or is REPLACE
            if (childMergeBehavior != null
                    && childMergeBehavior.getDefaultBehavior() == MergeBehaviorTree.MergeBehavior.REPLACE) {
                remove(children.get(child));
            }
        });
    }

    private void updateChild(long lastModified, String key, Object value, @NonNull MergeBehaviorTree mergeBehavior) {
        MergeBehaviorTree childMergeBehavior = mergeBehavior.getChildOverride().get(key);
        if (childMergeBehavior == null) {
            childMergeBehavior = mergeBehavior.getChildOverride().get(MergeBehaviorTree.WILDCARD);
        }

        if (childMergeBehavior == null) {
            childMergeBehavior = mergeBehavior;
        }

        switch (childMergeBehavior.getDefaultBehavior()) {
            case MERGE:
                mergeChild(lastModified, key, value, childMergeBehavior);
                break;
            case REPLACE:
                replaceChild(lastModified, key, value, childMergeBehavior);
                break;
            default:
        }
    }

    private void mergeChild(long lastModified, String key, Object value, @NonNull MergeBehaviorTree mergeBehavior) {
        if (value instanceof Map) {
            createInteriorChild(key).updateFromMap(lastModified, (Map) value, mergeBehavior);
        } else {
            createLeafChild(key).withNewerValue(lastModified, value);
        }
    }

    private void replaceChild(long lastModified, String key, Object value,
                              @Nonnull MergeBehaviorTree childMergeBehavior) {
        Node existingChild = children.get(key);
        // if new node is a container node
        if (value instanceof Map) {
            // if existing child is a leaf node
            // TODO: handle node type change between container/leaf node
            if (existingChild != null && !(existingChild instanceof Topics)) {
                remove(existingChild);
            }
            createInteriorChild(key).updateFromMap(lastModified, (Map) value, childMergeBehavior);
        // if new node is a leaf node
        } else {
            // if existing child is a container node
            if (existingChild != null && !(existingChild instanceof Topic)) {
                remove(existingChild);
            }
            createLeafChild(key).withNewerValue(lastModified, value);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Topics) {
            Topics t = (Topics) o;
            if (children.size() == t.children.size()) {
                for (Map.Entry<String, Node> me : children.entrySet()) {
                    Object mov = t.children.get(me.getKey());
                    if (!Objects.equals(me.getValue(), mov)) {
                        //                            System.out.println(me.getKey() + "\t" + me.getValue() +
                        //                                    "\n\t" + t.children.get(me.getKey()));
                        return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(children);
    }

    @Override
    @Nonnull
    public Iterator<Node> iterator() {
        return children.values().iterator();
    }

    @Override
    public void deepForEachTopic(Consumer<Topic> f) {
        children.values().forEach((t) -> t.deepForEachTopic(f));
    }

    /**
     * Remove a node from this node's children.
     *
     * @param n node to remove
     */
    public void remove(Node n) {
        if (!children.remove(n.getName(), n)) {
            logger.atError("config-node-child-remove-error").kv("thisNode", toString()).kv("childNode", n.getName())
                    .log();
            return;
        }
        context.runOnPublishQueue(() -> {
            n.fire(WhatHappened.removed);
            this.childChanged(WhatHappened.childRemoved, n);
        });
    }

    /**
     * Clears all the children nodes and replaces with the provided new map. Waits for replace to finish
     * @param newValue Map of new values for this topics
     */
    public void replaceAndWait(Map<Object, Object> newValue) {
        context.runOnPublishQueueAndWait(() -> replaceNode(newValue));
    }

    private void replaceNode(Map<Object, Object> newValue) {
        children.clear();
        mergeMap(System.currentTimeMillis(), newValue);
        this.fire(WhatHappened.changed);
    }

    protected void childChanged(WhatHappened what, Node child) {
        logger.atDebug().setEventType("config-node-child-update").addKeyValue("configNode", getFullName())
                .addKeyValue("reason", what.name()).log();

        for (Watcher s : watchers) {
            if (s instanceof ChildChanged) {
                ((ChildChanged) s).childChanged(what, child);
            }
            // TODO: detect if a subscriber fails. Possibly unsubscribe it if the fault is persistent
        }

        if (what.equals(WhatHappened.removed)) {
            children.forEach((k, v) -> v.fire(WhatHappened.removed));
            return;
        }

        if (child.modtime > this.modtime || children.isEmpty()) {
            this.modtime = child.modtime;
        } else {
            this.modtime = children.values().stream().max((node, other) -> {
                if (node.modtime == other.modtime) {
                    return 0;
                }
                if (node.modtime < other.modtime) {
                    return -1;
                }
                return 1;
            }).get().modtime;
        }
        if (parentNeedsToKnow()) {
            parent.childChanged(what, child);
        }
    }

    @Override
    protected void fire(WhatHappened what) {
        childChanged(what, null);
    }

    /**
     * Subscribe to receive updates from this node and its children.
     *
     * @param cc listener
     * @return this
     */
    public Topics subscribe(ChildChanged cc) {
        if (addWatcher(cc)) {
            cc.childChanged(WhatHappened.initialized, null);
        }
        return this;
    }

    @Override
    public Map<String, Object> toPOJO() {
        Map<String, Object> map = new TreeMap(String.CASE_INSENSITIVE_ORDER);
        children.values().forEach((n) -> {
            if (!n.getName().startsWith("_")) {
                // Don't save entries whose name starts in '_'
                map.put(n.getName(), n.toPOJO());
            }
        });
        return map;
    }

    public boolean isEmpty() {
        return children.isEmpty();
    }

    public Context getContext() {
        return this.context;
    }
}
