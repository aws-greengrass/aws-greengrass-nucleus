/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import java.io.*;
import java.nio.file.*;
import org.junit.*;
import static org.junit.Assert.*;

public class UnsyncBufferedOutputStreamTest {
    OutputStream out;
    CommitableFile cf;
    Path t = Paths.get("/tmp/UBOS.tmp");
    @Test
    public void T1() {
//        Tb(1<<13);
//        Tb(23);
        Tb((1<<11)-1);
    }
    private void Tb(int n) {
        v = 0;
        System.out.println("Buffer size "+n);
        try {
            out = UnsyncBufferedOutputStream.of(cf = CommitableFile.abandonOnClose(t), n);
            for (int i = 20; --i >= 0;) {
                w();
                w(13);
                w();
                w();
                w(1000);
            }
            out.flush();
            cf.commit();
            assertEquals("Size check", v, Files.size(t));
            byte[] in = Files.readAllBytes(t);
            assertEquals("Size check 2", v, in.length);
            for(int i = 0; i<in.length; i++) 
                assertEquals(i&0xFF, in[i]&0xFF);
        } catch (IOException ex) {
            ex.printStackTrace(System.out);
        }
    }
    int v = 0;
    private void w() throws IOException {
        out.write(v++);
    }
    private void w(int n) throws IOException {
        byte[] buf = new byte[n];
        for (int i = 0; i < n; i++)
            buf[i] = (byte) v++;
        out.write(buf);
    }

}
