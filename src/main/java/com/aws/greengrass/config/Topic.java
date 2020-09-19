/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.config;

import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public class Topic extends Node {

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
        if (addWatcher(s)) {
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
    public Topic addValidator(Validator validator) {
        if (addWatcher(validator) && value != null) {
            value = validator.validate(value, null);
        }
        return this;
    }

    /**
     * This should rarely be used. Instead, use subscribe(Subscriber).
     * Not synchronized with setState(). The returned value is the value of the last completed setState().
     */
    public Object getOnce() {
        return value;
    }

    @Override
    public void appendTo(Appendable a) throws IOException {
        appendNameTo(a);
        a.append(':');
        a.append(String.valueOf(value));
    }

    public Topic withValueChecked(Object nv) throws UnsupportedInputTypeException {
        return withValueChecked(System.currentTimeMillis(), nv);
    }

    /**
     * Set value with checked exception if the type will not store properly.
     *
     * @param proposedModtime proposed modification time
     * @param nv value
     * @return this
     * @throws UnsupportedInputTypeException if the input value is unsupported
     */
    public Topic withValueChecked(long proposedModtime, Object nv) throws UnsupportedInputTypeException {
        if (nv == null || nv instanceof String || nv instanceof Number || nv instanceof Boolean || nv instanceof List) {
            return withNewerValue(proposedModtime, nv);
        }
        throw new UnsupportedInputTypeException(nv.getClass());
    }

    private Topic withValue(Object nv) {
        return withNewerValue(System.currentTimeMillis(), nv);
    }

    public Topic withValue(String nv) {
        return withValue((Object) nv);
    }

    public Topic withValue(Number nv) {
        return withValue((Object) nv);
    }

    public Topic withValue(Boolean nv) {
        return withValue((Object) nv);
    }

    public Topic withValue(List<String> nv) {
        return withValue((Object) nv);
    }

    // Because of type erasure, the following overrides need to have different names
    public Topic withValueLN(List<Number> nv) {
        return withValue(nv);
    }

    public Topic withValueLB(List<Boolean> nv) {
        return withValue(nv);
    }

    Topic withNewerValue(long proposedModtime, final Object proposed) {
        return withNewerValue(proposedModtime, proposed, false);
    }

    /**
     * Set the value of this topic to a new value.
     *
     * @param proposedModtime the last modified time of the value. If this is in the past, we do not update the value.
     * @param proposed        new value.
     * @return this.
     */
    public Topic withNewerValue(long proposedModtime, String proposed) {
        return withNewerValue(proposedModtime, proposed, false);
    }

    /**
     * Set the value of this topic to a new value.
     *
     * @param proposedModtime the last modified time of the value. If this is in the past, we do not update the value.
     * @param proposed        new value.
     * @return this.
     */
    public Topic withNewerValue(long proposedModtime, Number proposed) {
        return withNewerValue(proposedModtime, proposed, false);
    }

    /**
     * Set the value of this topic to a new value.
     *
     * @param proposedModtime the last modified time of the value. If this is in the past, we do not update the value
     *                       unless this is forced
     * @param proposed        new value.
     * @param forceTimestamp indicate if the proposed time should be forced.
     * @return this.
     */
    synchronized Topic withNewerValue(long proposedModtime, final Object proposed, boolean forceTimestamp) {
        final Object currentValue = value;
        final long currentModtime = modtime;
        if (Objects.equals(proposed, currentValue) || !forceTimestamp && (proposedModtime < currentModtime)) {
            return this;
        }
        final Object validated = validate(proposed, currentValue);
        if (Objects.equals(validated, currentValue)) {
            return this;
        }

        if (validated != null && !(validated instanceof String) && !(validated instanceof Number)
                && !(validated instanceof Boolean) && !(validated instanceof List) && !(validated instanceof Enum)) {
            // Log with cause if it is an invalid type. This will trip our test protection without actually causing an
            // exception and failing the write
            logger.atError().cause(new UnsupportedInputTypeException(proposed.getClass()))
                    .kv("path", path()).kv("value", validated).log();
        }

        value = validated;
        modtime = proposedModtime;
        context.runOnPublishQueue(() -> this.fire(WhatHappened.changed));
        return this;
    }

    @Override
    protected void fire(WhatHappened what) {
        logger.atDebug().setEventType("config-node-update").addKeyValue("configNode", getFullName())
                .addKeyValue("reason", what.name()).log();
        for (Watcher s : watchers) {
            if (s instanceof Subscriber) {
                ((Subscriber) s).published(what, this);
            }
        }

        // in the case of 'removed' event, parents are already notified with 'childRemoved'.
        if (WhatHappened.removed.equals(what)) {
            return;
        }

        // in the case of 'changed' event
        if (parentNeedsToKnow()) {
            parent.childChanged(WhatHappened.childChanged, this);
        }
    }

    @Override
    public void copyFrom(Node n) {
        if (n instanceof Topic) {
            withNewerValue(n.modtime, ((Topic) n).value);
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
            withNewerValue(1, dflt); // defaults come from the dawn of time
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
