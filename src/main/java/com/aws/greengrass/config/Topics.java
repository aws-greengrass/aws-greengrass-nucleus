/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

public class Topics extends Node implements Iterable<Node> {
    public final Map<CaseInsensitiveString, Node> children = new ConcurrentHashMap<>();

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
        return children.get(new CaseInsensitiveString(name));
    }

    /**
     * Create a leaf Topic under this Topics with the given name.
     * Returns the leaf topic if it already existed.
     *
     * @param name name of the leaf node
     * @return the node
     */
    public Topic createLeafChild(String name) {
        return createLeafChild(new CaseInsensitiveString(name));
    }

    private Topic createLeafChild(CaseInsensitiveString name) {
        Node n = children.computeIfAbsent(name,
                (nm) -> new Topic(context, nm.toString(), this));
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
       return createInteriorChild(new CaseInsensitiveString(name));
    }

    private Topics createInteriorChild(CaseInsensitiveString name) {
        Node n = children.computeIfAbsent(name,
                (nm) -> new Topics(context, nm.toString(), this));
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
     * @param path String[] of node names to traverse to find the Topic
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
     * @param path     String[] of node names to traverse to find the Topic
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
     * @param path String[] of node names to traverse to the Topic
     */
    public Topics findTopics(String... path) {
        int limit = path.length;
        Topics n = this;
        for (int i = 0; i < limit && n != null; i++) {
            n = n.findInteriorChild(path[i]);
        }
        return n;
    }

    /**
     * Find, but do not create if missing, a Node (Topic or Topics) in the config file. Returns null if missing.
     *
     * @param path String[] of node names to traverse to find the Node
     * @return Node instance found after traversing the given path
     */
    public Node findNode(String... path) {
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
     * @param map           map to merge in
     * @param mergeBehavior mergeBehavior
     */
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH")
    public void updateFromMap(Map<String, Object> map, @NonNull UpdateBehaviorTree mergeBehavior) {
        if (map == null) {
            logger.atInfo().kv("node", getFullName()).log("Null map received in updateFromMap(), ignoring.");
            return;
        }
        Set<CaseInsensitiveString> childrenToRemove = new HashSet<>(children.keySet());

        map.forEach((okey, value) -> {
            CaseInsensitiveString key = new CaseInsensitiveString(okey);
            childrenToRemove.remove(key);
            updateChild(key, value, mergeBehavior);
        });

        childrenToRemove.forEach(childName -> {
            UpdateBehaviorTree childMergeBehavior = mergeBehavior.getBehavior(childName.toString());

            // remove the existing child if its merge behavior is not present and root merge behavior is REPLACE
            if (childMergeBehavior == null
                    && mergeBehavior.getDefaultBehavior() == UpdateBehaviorTree.UpdateBehavior.REPLACE) {
                remove(children.get(childName));
                return;
            }

            // remove the existing child if its merge behavior is REPLACE
            if (childMergeBehavior != null
                    && childMergeBehavior.getDefaultBehavior() == UpdateBehaviorTree.UpdateBehavior.REPLACE) {
                remove(children.get(childName));
            }
        });
    }

    private void updateChild(CaseInsensitiveString key, Object value,
                             @NonNull UpdateBehaviorTree mergeBehavior) {
        UpdateBehaviorTree childMergeBehavior = mergeBehavior.getBehavior(key.toString());

        if (childMergeBehavior == null) {
            childMergeBehavior = mergeBehavior;
        }

        switch (childMergeBehavior.getDefaultBehavior()) {
            case MERGE:
                mergeChild(key, value, childMergeBehavior);
                break;
            case REPLACE:
                replaceChild(key, value, childMergeBehavior);
                break;
            default:
        }
    }

    private void mergeChild(CaseInsensitiveString key, Object value,
                            @NonNull UpdateBehaviorTree mergeBehavior) {
        if (value instanceof Map) {
            createInteriorChild(key).updateFromMap((Map) value, mergeBehavior);
        } else {
            createLeafChild(key).withNewerValue(mergeBehavior.getTimestampToUse(), value, false, true);
        }
    }

    private void replaceChild(CaseInsensitiveString key, Object value,
                              @Nonnull UpdateBehaviorTree childMergeBehavior) {
        Node existingChild = children.get(key);
        // if new node is a container node
        if (value instanceof Map) {
            // if existing child is a leaf node
            // TODO: handle node type change between container/leaf node
            if (existingChild != null && !(existingChild instanceof Topics)) {
                remove(existingChild);
            }
            createInteriorChild(key).updateFromMap((Map) value, childMergeBehavior);
        // if new node is a leaf node
        } else {
            // if existing child is a container node
            if (existingChild != null && !(existingChild instanceof Topic)) {
                remove(existingChild);
            }
            createLeafChild(key).withNewerValue(childMergeBehavior.getTimestampToUse(), value, false, true);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Topics) {
            Topics t = (Topics) o;
            if (children.size() == t.children.size()) {
                for (Map.Entry<CaseInsensitiveString, Node> me : children.entrySet()) {
                    Object mov = t.children.get(me.getKey());
                    if (!Objects.equals(me.getValue(), mov)) {
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
        if (!children.remove(new CaseInsensitiveString(n.getName()), n)) {
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
    public void replaceAndWait(Map<String, Object> newValue) {
        context.runOnPublishQueueAndWait(() ->
                updateFromMap(newValue,
                        new UpdateBehaviorTree(UpdateBehaviorTree.UpdateBehavior.REPLACE, System.currentTimeMillis()))
        );
        context.runOnPublishQueueAndWait(() -> {});
    }

    protected void childChanged(WhatHappened what, Node child) {
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
            Optional<Node> n = children.values().stream().max(Comparator.comparingLong(node -> node.modtime));
            this.modtime = n.orElse(child).modtime;
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
        Map<String, Object> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
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
