/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.aws.iot.evergreen.util;

import org.junit.jupiter.api.Test;
import software.amazon.ion.IonReader;
import software.amazon.ion.IonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static com.aws.iot.evergreen.util.POJOUtil.writeFinishPOJO;
import static com.aws.iot.evergreen.util.Utils.deepToString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsonUtilTest {

    final Map m = new HashMap();

    {
        m.put("a", "hello");
        m.put("b", 42);
        m.put("c", 7.5);
        m.put("d", new int[]{1, 2, 3});
        Collection s1 = new HashSet();
        s1.add("foo");
        s1.add(17);
        m.put("e", s1);
        Collection s2 = new ArrayList();
        s2.add("bar");
        s2.add(18);
        m.put("f", s2);
        m.put("g", true);
        m.put("NaN", Float.NaN);
    }

    @Test
    public void test1() {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        IonWriter out = POJOUtil.writerBuilder.build(bout);
        try {
            writeFinishPOJO(out, m);
            writeFinishPOJO(out, m);  // write a second time
            //            System.out.println(bout);
            IonReader in = POJOUtil.readerBuilder.build(bout.toByteArray());
            Object nm = POJOUtil.readPOJO(in);
            Object nm2 = POJOUtil.readPOJO(POJOUtil.readerBuilder.build(bout.toByteArray()));
            System.out.println(deepToString(m, 80));
            System.out.println(deepToString(nm, 80));
            System.out.println(deepToString(nm2, 80));
            assertTrue(nm instanceof Map);
            assertTrue(nm2 instanceof Map);
            Map m2 = (Map) nm;
            Map m3 = (Map) nm2;
            assertEquals(m.size(), m2.size());
            assertEquals(m.size(), m3.size());
            assertEquals("hello", m3.get("a"));
            assertEquals(42, m3.get("b"));
        } catch (IOException ex) {
            ex.printStackTrace(System.out);
        }
    }

}
