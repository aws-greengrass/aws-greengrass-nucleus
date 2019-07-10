/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.config;

import java.io.*;
import java.util.*;
import java.util.function.*;

public class Topic extends Node {
    Topic(String n, Topics p) {
        super(n, p);
    }
    private long modtime;
    Object value;
    /**
     * This is the preferred way to get a value from a configuration.
     * Instead of {@code setValue(configValue.getOnce()) }
     * use {@code configValue.get((nv,ov)->setValue(nv)) }
     * This way, every change to the config file will get forwarded to the
     * object.
     *
     * @param s
     */
    public Subscriber subscribe(Subscriber s) {
        listen(s);
        try {
            s.published(Configuration.WhatHappened.initialized, value, value);
        } catch (Throwable ex) {
            //TODO: do something less stupid
        }
        return s;
    }
    public Validator validate(Validator s) {
        listen(s);
        try {
            Object nv = s.validate(value, value);
        } catch (Throwable ex) {
            //TODO: do something less stupid
        }
        return s;
    }
    public long getModtime() {
        return modtime;
    }
    /**
     * This should rarely be used. Instead, use subscribe(Subscriber)
     */
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
    public void setValue(Object nv) {
        setValue(System.currentTimeMillis(), nv);
    }
    public synchronized void setValue(long mt, Object nv) {
        final Object ov = value;
        final long omt = modtime;
        if (!Objects.equals(nv, ov) && mt >= omt) {
            nv = validate(Configuration.WhatHappened.changed, nv, ov);
            if (!Objects.equals(nv, ov)) {
                value = nv;
                modtime = mt;
                fire(Configuration.WhatHappened.changed, nv, ov);
                parent.publish(this);
            }
        }
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
