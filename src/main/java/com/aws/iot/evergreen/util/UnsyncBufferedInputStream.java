/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.evergreen.util;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public class UnsyncBufferedInputStream extends FilterInputStream {
    private static final int bufferSize = 1 << 14;
    private byte[] buf = new byte[bufferSize];
    private int pos, size;
    private int markPos = -1;

    private UnsyncBufferedInputStream(InputStream in) {
        super(in);
    }

    public static InputStream of(File f) throws IOException {
        return new MappedInputStream(f);
    }

    public static InputStream of(Path f) throws IOException {
        return new MappedInputStream(f);
    }

    public static InputStream of(InputStream f) throws IOException {
        return f instanceof UnsyncBufferedInputStream || f instanceof MappedInputStream ? f :
                new BufferedInputStream(f);
    }

    private boolean fill() throws IOException {
        if (pos < size) {
            return true;
        }
        pos = 0;
        size = in.read(getBuf());
        markPos = -1;
        return size > 0;
    }

    private byte[] getBuf() throws IOException {
        byte[] r = buf;
        if (r == null) {
            throw new IOException("File closed");
        }
        return r;
    }

    @Override
    public int read() throws IOException {
        if (!fill()) {
            return -1;
        }
        return buf[pos++] & 0xFF;
    }

    @Override
    public long skip(long n) throws IOException {
        long remaining = size - pos - n;
        if (remaining >= 0) {
            pos += n;
            return n;
        }
        pos = size;
        return in.skip(n + remaining) - remaining;
    }

    @Override
    public int available() {
        return size - pos;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!fill()) {
            return -1;
        }
        int available = size - pos;
        if (available < len) {
            len = available;
        }
        System.arraycopy(buf, pos, b, off, len);
        pos += len;
        return len;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void reset() throws IOException {
        if (markPos < 0) {
            throw new IOException("Mark is invalid");
        }
        pos = markPos;
    }

    @Override
    public void mark(int readlimit) {
        markPos = pos;
    }

    @Override
    public void close() throws IOException {
        if (buf != null) {
            buf = null;
            in.close();
        }
    }
}
