/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import java.io.IOException;
import java.io.Writer;
import javax.annotation.Nonnull;

/**
 * For writing to an Appendable.
 */
public class AppendableWriter extends Writer {
    private final Appendable appendable;

    public AppendableWriter(Appendable appendable) {
        this.appendable = appendable;
    }

    @Override
    public void write(@Nonnull char[] buf, int offset, int len) throws IOException {
        while (--len >= 0) {
            appendable.append(buf[offset++]);
        }
    }

    @Override
    public void write(int i) throws IOException {
        appendable.append((char) i);
    }

    @Override
    public void write(@Nonnull String str, int offset, int len) throws IOException {
        appendable.append(str, offset, offset + len);
    }

    @Override
    public Writer append(char i) throws IOException {
        appendable.append(i);
        return this;
    }

    @Override
    public Writer append(CharSequence csq) throws IOException {
        appendable.append(csq);
        return this;
    }

    @Override
    public Writer append(CharSequence csq, int start, int end) throws IOException {
        appendable.append(csq, start, end);
        return this;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
