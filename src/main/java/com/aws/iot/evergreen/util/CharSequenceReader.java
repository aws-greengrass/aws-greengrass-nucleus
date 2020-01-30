/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import java.io.IOException;
import java.io.Reader;

public class CharSequenceReader extends Reader {
    private final CharSequence chars;
    private int pos = 0;
    private int mark;

    public CharSequenceReader(CharSequence cs) {
        chars = cs;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        if (pos + len > chars.length()) {
            len = Integer.max(0, chars.length() - pos);
        }
        int limit = off + len;
        while (off < limit) {
            cbuf[off++] = chars.charAt(pos++);
        }
        return len;
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public int read() throws IOException {
        return pos < chars.length() ? chars.charAt(pos++) : -1;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void mark(int readAheadLimit) throws IOException {
        mark = pos;
    }

    @Override
    public void reset() throws IOException {
        pos = mark;
    }

    @Override
    public boolean ready() throws IOException {
        return true;
    }

    @Override
    public long skip(long n) throws IOException {
        int np = Integer.max(0, Integer.min(chars.length(), pos + (int) n));
        int ret = np - pos;
        pos = np;
        return ret;
    }
}