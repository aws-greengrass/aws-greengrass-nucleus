/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import java.io.*;

public class UnsyncBufferedOutputStream extends FilterOutputStream {
    private byte[] buffer;
    private int pos;
    private UnsyncBufferedOutputStream(OutputStream out, int bsize) {
        super(out);
        buffer = new byte[Math.max(bsize, 64)];
    }
    public static OutputStream of(OutputStream outputStream) {
        return of(outputStream, 1 << 13);
    }
    public static OutputStream of(OutputStream outputStream, int sz) {
        return outputStream instanceof UnsyncBufferedOutputStream ? outputStream
                : new UnsyncBufferedOutputStream(outputStream, sz);
    }
    @Override
    public void write(int b) throws IOException {
        byte[] f = buffer;
        if (pos >= f.length)
            flush();
        f[pos++] = (byte) b;
    }
    @Override
    public void write(byte b[], int off, int len) throws IOException {
        byte[] f = buffer;
        if (pos + len <= f.length) {
            System.arraycopy(b, off, f, pos, len);
            pos += len;
        } else {
            flush();
            if (len < f.length) {
                System.arraycopy(b, off, f, pos, len);
                pos = len;
            } else
                out.write(b, off, len);
        }
    }
    @Override
    public void flush() throws IOException {
        if (pos > 0 && buffer != null) {
            out.write(buffer, 0, pos);
            pos = 0;
        }
    }
    @Override
    public void close() throws IOException {
        if (buffer != null) {
            out.close();
            buffer = null;
            out = null;
        }
    }

}
