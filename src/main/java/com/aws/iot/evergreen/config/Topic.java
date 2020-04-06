/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.config;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

public class Topic extends Node {
    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need for modtime to be sync")
    private long modtime;
    private Object value;

    private static final Logger logger = LogManager.getLogger(Topic.class);

    Topic(Context c, String n, Topics p) {
        super(c, n, p);
    }

    public static Topic of(Context c, String n, Object v) {
        return new Topic(c, n, null).dflt(v);
    }

    /**
     * Subscribe to a topic and invoke the subscriber right away on the same thread for a new subscriber.
     * <p>
     * This is the preferred way to get a value from a configuration. Instead of {@code setValue(configValue.getOnce())}
     * use {@code configValue.get((nv,ov)->setValue(nv)) }
     * This way, every change to the config file will get forwarded to the object.
     *</p>
     *
     * @param s subscriber
     * @return this topic
     */
    public Topic subscribe(Subscriber s) {
        if (listen(s)) {
            // invoke the new subscriber right away
            s.published(WhatHappened.initialized, this);
        }
        return this;
    }

    /**
     * Add a validator to the topic and immediately validate the current value.
     *
     * @param validator validator
     * @return this
     */
    public Topic validate(Validator validator) {
        if (listen(validator)) {
            try {
                if (value != null) {
                    value = validator.validate(value, null);
                }
            } catch (Throwable ex) {
                //TODO: do something less stupid
            }
        }
        return this;
    }

    public long getModtime() {
        return modtime;
    }

    /**
     * This should rarely be used. Instead, use subscribe(Subscriber).
     * Not synchronized with setState(). The returned value is the value of the last completed setState().
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

    public Topic setValue(Object nv) {
        return setValue(System.currentTimeMillis(), nv);
    }

    /**
     * Set the value of this topic to a new value.
     *
     * @param proposedModtime the last modified time of the value. If this is in the past, we do not update the value.
     * @param proposed        new value.
     * @return this.
     */
    public synchronized Topic setValue(long proposedModtime, final Object proposed) {
        //        context.getLog().note("proposing change to "+getFullName()+": "+value+" => "+proposed);
        //        System.out.println("setValue: " + getFullName() + ": " + value + " => " + proposed);
        //        if(proposed==Errored)
        //            new Exception("setValue to Errored").printStackTrace();
        final Object currentValue = value;
        final long currentModtime = modtime;
        if (Objects.equals(proposed, currentValue) || proposedModtime < currentModtime) {
            return this;
        }
        final Object validated = validate(proposed, currentValue);
        if (Objects.equals(validated, currentValue)) {
            return this;
        }
        value = validated;
        modtime = proposedModtime;
        //        context.getLog().note("seen change to "+getFullName()+": "+currentValue+" => "+validated);
        context.queuePublish(this);
        return this;
    }

    @Override
    public void fire(WhatHappened what) {
        logger.atDebug().setEventType("config-node-update").addKeyValue("configNode", getFullName())
                .addKeyValue("reason", what.name()).log();
        if (watchers != null) {
            for (Watcher s : watchers) {
                try {
                    if (s instanceof Subscriber) {
                        ((Subscriber) s).published(what, this);
                    }
                } catch (Throwable ex) {
                    /* TODO if a subscriber fails, we should do more than just log a
                       message.  Possibly unsubscribe it if the fault is persistent */
                    logger.atError().setCause(ex).setEventType("config-node-update-error")
                            .addKeyValue("configNode", getFullName()).addKeyValue("subscriber", s.toString())
                            .addKeyValue("reason", what.name()).log();
                }
            }
        }
        if (parent != null && parentNeedsToKnow()) {
            parent.childChanged(what, this);
        }
    }

    @Override
    public void copyFrom(Node n) {
        if (n instanceof Topic) {
            setValue(((Topic) n).modtime, ((Topic) n).value);
        } else {
            throw new IllegalArgumentException(
                    "copyFrom: " + (n == null ? "NULL" : n.getFullName()) + " is already a container, not a leaf");
        }
    }

    /**
     * Set a default value for the topic.
     *
     * @param dflt the default value
     * @return this
     */
    public synchronized Topic dflt(Object dflt) {
        if (value == null) {
            setValue(1, dflt); // defaults come from the dawn of time
        }
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Topic) {
            Topic t = (Topic) o;
            return getName().equals(t.getName());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getName());
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
