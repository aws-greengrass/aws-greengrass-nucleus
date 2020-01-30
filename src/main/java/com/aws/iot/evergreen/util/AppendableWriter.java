/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.util;

import java.io.IOException;
import java.io.Writer;

/**
 * For writing to an Appendable
 */
public class AppendableWriter extends Writer {
    private final Appendable a;

    public AppendableWriter(Appendable A) {
        a = A;
    }

    @Override
    public void write(char[] buf, int offset, int len) throws IOException {
        while (--len >= 0) {
            a.append(buf[offset++]);
        }
    }

    @Override
    public void write(int i) throws IOException {
        a.append((char) i);
    }

    @Override
    public void write(String str, int offset, int len) throws IOException {
        a.append(str, offset, offset + len);
    }

    @Override
    public Writer append(char i) throws IOException {
        a.append((char) i);
        return this;
    }

    @Override
    public Writer append(CharSequence csq) throws IOException {
        a.append(csq);
        return this;
    }

    @Override
    public Writer append(CharSequence csq, int start, int end) throws IOException {
        a.append(csq, start, end);
        return this;
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
