/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */


package com.aws.iot.evergreen.util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.annotation.Nonnull;

/**
 * Just like FileInputStream, except that it mmaps the file into the address space,
 * insteading of reading it block by block.  Only really speeds things up when the
 * file is large.
 */
public class MappedInputStream extends InputStream {
    private final FileChannel ch;
    private final MappedByteBuffer mb;

    public MappedInputStream(Path p) throws IOException {
        ch = FileChannel.open(p);
        mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
    }

    public MappedInputStream(File p) throws IOException {
        this(p.toPath());
    }

    public MappedInputStream(String p) throws IOException {
        this(Paths.get(p));
    }

    @Override
    public int read() {
        return mb.remaining() > 0 ? mb.get() & 0xFF : -1;
    }

    @Override
    public int read(@Nonnull byte[] buf, int off, int len) {
        if (mb.remaining() < len) {
            len = mb.remaining();
            if (len <= 0) {
                return -1;
            }
        }
        mb.get(buf, off, len);
        return len;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public void mark(int limit) {
        mb.mark();
    }

    @Override
    public void reset() {
        mb.reset();
    }

    @Override
    public int available() {
        return mb.limit() - mb.position();
    }

    @Override
    public long skip(long n) {
        mb.position((int) (mb.position() + n));
        return n;
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
