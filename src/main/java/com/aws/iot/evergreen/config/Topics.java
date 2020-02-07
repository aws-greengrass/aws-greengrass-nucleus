/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

public class Topics extends Node implements Iterable<Node> {
    public final ConcurrentHashMap<String, Node> children = new ConcurrentHashMap<>();

    Topics(Context c, String n, Topics p) {
        super(c, n, p);
    }

    public static Topics errorNode(Context context, String name, String message) {
        Topics t = new Topics(context, name, null);
        t.createLeafChild("error").setValue(0, message);
        return t;
    }

    public void appendValueTo(Appendable a) throws IOException {
        a.append(String.valueOf(children));
    }

    @Override
    public void appendTo(Appendable a) throws IOException {
        appendNameTo(a);
        a.append(':');
        appendValueTo(a);
    }

    public int size() {
        return children.size();
    }

    @Override
    public void copyFrom(Node from) {
        assert (from != null);
        if (from instanceof Topics) {
            ((Topics) from).forEach(n -> {
                assert (n != null);
                if (n instanceof Topic) {
                    createLeafChild(n.name).copyFrom(n);
                } else {
                    createInteriorChild(n.name).copyFrom(n);
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

    public Topic createLeafChild(String name) {
        Node n = children.computeIfAbsent(name, (nm) -> new Topic(context, nm, Topics.this));
        if (n instanceof Topic) {
            return (Topic) n;
        } else {
            throw new IllegalArgumentException(name + " in " + this + " is already a container, cannot become a leaf");
        }
    }

    public Topics createInteriorChild(String name) {
        Node n = children.computeIfAbsent(name, (nm) -> new Topics(context, nm, Topics.this));
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
     */
    public Topic lookup(String... path) {
        int limit = path.length - 1;
        Topics n = this;
        for (int i = 0; i < limit; i++) {
            n = n.createInteriorChild(path[i]);
        }
        return n.createLeafChild(path[limit]);
    }

    public void publish(Topic t) {
        childChanged(WhatHappened.childChanged, t);
    }

    public void mergeMap(long t, Map<Object, Object> map) {
        map.forEach((okey, value) -> {
            String key = okey.toString();
            if (value instanceof Map) {
                createInteriorChild(key).mergeMap(t, (Map) value);
            } else {
                createLeafChild(key).setValue(t, value);
            }
        });
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

    public void forEachTopicSet(Consumer<Topics> f) {
        children.values().forEach((t) -> {
            if (t instanceof Topics) {
                f.accept((Topics) t);
            }
        });
    }

    public void remove(Node n) {
        if (!children.remove(n.name, n)) {
            System.err.println("remove: Missing node " + n.name + " from " + toString());
        }
        n.fire(WhatHappened.removed);
        childChanged(WhatHappened.childRemoved, n);
    }

    protected void childChanged(WhatHappened what, Node child) {
        if (watchers != null) {
            for (Watcher s : watchers) {
                try {
                    if (s instanceof ChildChanged) {
                        ((ChildChanged) s).childChanged(what, child);
                    }
                } catch (Throwable ex) {
                    /* TODO if a subscriber fails, we should do more than just log a
                       message.  Possibly unsubscribe it if the fault is persistent */
                    context.getLog().error(getFullName(), ex);
                }
            }
        }
        if (parent != null && parentNeedsToKnow()) {
            parent.childChanged(WhatHappened.childChanged, this);
        }
    }

    @Override
    protected void fire(WhatHappened what) {
        childChanged(what, null);
    }

    public Topics subscribe(ChildChanged cc) {
        if (listen(cc)) {
            try {
                cc.childChanged(WhatHappened.initialized, null);
            } catch (Throwable ex) {
                //TODO: do something less stupid
            }
        }
        return this;
    }

    @Override
    public Map<String, Object> toPOJO() {
        Map<String, Object> map = new TreeMap(String.CASE_INSENSITIVE_ORDER);
        children.values().forEach((n) -> {
            if (!n.name.startsWith("_")) {
                // Don't save entries whose name starts in '_'
                map.put(n.name, n.toPOJO());
            }
        });
        return map;
    }

    public boolean isEmpty() {
        return children.isEmpty();
    }
}
