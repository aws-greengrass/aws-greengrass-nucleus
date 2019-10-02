/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.config;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class Topic extends Node {
    Topic(String n, Topics p) {
        super(n, p);
    }
    public static Topic of(String n, Object v) {
        return new Topic(n, null).dflt(v);
    }
    private long modtime;
    private Object value;
    /**
     * This is the preferred way to get a value from a configuration. Instead of {@code setValue(configValue.getOnce())
     * }
     * use {@code configValue.get((nv,ov)->setValue(nv)) }
     * This way, every change to the config file will get forwarded to the
     * object.
     *
     * @param s
     */
    public Topic subscribe(Subscriber s) {
        listen(s);
        try {
            s.published(Configuration.WhatHappened.initialized, value, value);
        } catch (Throwable ex) {
            //TODO: do something less stupid
        }
        return this;
    }
    public Topic validate(Validator s) {
        listen(s);
        try {
            Object nv = s.validate(value, value);
        } catch (Throwable ex) {
            //TODO: do something less stupid
        }
        return this;
    }
    public long getModtime() {
        return modtime;
    }
    /**
     * This should rarely be used. Instead, use subscribe(Subscriber)
     */
    // @Deprecated
    public Object getOnce() {
        return value;
    }
    public void appendValueTo(Appendable a) throws IOException {
        a.append(String.valueOf(value));
    }
    @Override
    public void appendTo(Appendable a) throws IOException {
        appendNameTo(a);
        a.append(':');
        appendValueTo(a);
    }
    public Topic setValue(Object nv) {
        return setValue(System.currentTimeMillis(), nv);
    }
    public synchronized Topic setValue(long proposedModtime, final Object proposed) {
//        System.out.println("setValue: " + getFullName() + ": " + value + " => " + proposed);
//        if(proposed==Errored)
//            new Exception("setValue to Errored").printStackTrace();
        final Object currentValue = value;
        final long currentModtime = modtime;
        if (Objects.equals(proposed, currentValue) || proposedModtime < currentModtime)
            return this;
        final Object validated = validate(Configuration.WhatHappened.changed, proposed, currentValue);
        if (Objects.equals(validated, currentValue)) return this;
        value = validated;
        modtime = proposedModtime;
        serialized.add(() -> {
            fire(Configuration.WhatHappened.changed, validated, currentValue);
            if (parent != null) parent.publish(this);
        });
        return this;
    }
    private static BlockingDeque<Runnable> serialized
            = new LinkedBlockingDeque<>();
    static {
        new Thread() {
            {
                setName("Serialized listener processor");
                setPriority(Thread.MAX_PRIORITY - 1);
                setDaemon(true);
            }
            @Override
            public void run() {
                while (true)
                    try {
                        serialized.takeFirst().run();
                    } catch (Throwable t) {
                        t.printStackTrace(System.out);
                    }
            }
        }.start();
    }
    @Override public void copyFrom(Node n) {
        if (n instanceof Topic)
            setValue(((Topic) n).modtime, ((Topic) n).value);
        else
            throw new IllegalArgumentException("copyFrom: " + 
                    (n==null ? "NULL" : n.getFullName())
                    + " is already a container, not a leaf");
    }
    public synchronized Topic dflt(Object v) {
        if (value == null) setValue(1, v); // defaults come from the dawn of time
        return this;
    }
    @Override
    public boolean equals(Object o) {
        if (o instanceof Topic) {
            Topic t = (Topic) o;
            return name.equals(t.name) && Objects.equals(value, t.value);
        }
        return false;
    }
    @Override
    public int hashCode() {
        return 43 * Objects.hashCode(name) + Objects.hashCode(this.value);
    }
    @Override
    public void deepForEachTopic(Consumer<Topic> f) {
        f.accept(this);
    }
    @Override
    public Object toPOJO() {
        return value;
    }

}
