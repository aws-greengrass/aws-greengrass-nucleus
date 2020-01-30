/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import software.amazon.ion.IonReader;
import software.amazon.ion.IonType;
import software.amazon.ion.IonWriter;
import software.amazon.ion.system.IonReaderBuilder;
import software.amazon.ion.system.IonTextWriterBuilder;
import software.amazon.ion.system.IonWriterBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class POJOUtil {

    public static final IonReaderBuilder readerBuilder = IonReaderBuilder.standard().immutable();
    //    public static final IonWriterBuilder writerBuilder = IonBinaryWriterBuilder.standard().immutable();
    public static final IonWriterBuilder writerBuilder = IonTextWriterBuilder.pretty().immutable();
    public static final Object EOF = new Object() {
        @Override
        public String toString() {
            return "EOF";
        }
    };

    public static HashMap map(Object... kvs) {
        HashMap ret = new HashMap();
        int limit = kvs.length - 1;
        for (int i = 0; i < limit; i += 2) {
            ret.put(kvs[i], kvs[i + 1]);
        }
        return ret;
    }

    public static boolean isError(Map v) {
        return jsget(v, "error") != null;
    }

    public static String getError(Map v) {
        return Objects.toString(jsget(v, "error"), "");
    }

    public static Object jsget(Map v, String key) {
        return v.get(key);
    }

    public static Object jsget(Object v, String key) {
        return v instanceof Map ? ((Map) v).get(key) : null;
    }

    public static String jsstr(Object v) {
        if (v == null) {
            return null;
        }
        String s = Utils.deepToString(v).toString().trim();
        return Utils.isEmpty(s) ? null : s;
    }

    public static String jsstr(Object v, String key) {
        return jsstr(jsget(v, key));
    }

    public static String jsstr(Map v, String key) {
        return jsstr(jsget(v, key));
    }

    public static Object jsget(Object v, int key) {
        return v == null ? null : v.getClass().isArray() ? Array.get(v, key) : null;
    }

    public static int jsint(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException t) {
            return 0;
        }
    }

    public static Object readPOJO(IonReader in) {
        Object ret;
        IonType t = in.getType();
        if (t == null && in.next() == null) {
            return EOF;
        }
        switch (in.getType()) {
            case SYMBOL:
            case STRING:
                ret = in.stringValue();
                break;
            case INT:
                switch (in.getIntegerSize()) {
                    case INT:
                        ret = in.intValue();
                        break;
                    case LONG:
                        ret = in.longValue();
                        break;
                    case BIG_INTEGER:
                        ret = in.bigIntegerValue();
                        break;
                    default:
                        ret = null;
                        break;
                }
                break;
            case DECIMAL:
            case FLOAT:
                ret = in.doubleValue();
                break;
            case TIMESTAMP:
                ret = in.dateValue();
                break;
            case BOOL:
                ret = in.booleanValue();
                break;
            case CLOB:
            case BLOB:
                ret = in.newBytes();
                break;
            case STRUCT: {
                Map m = new HashMap();
                in.stepIn();
                while (in.next() != null) {
                    m.put(in.getFieldName(), readPOJO(in));
                }
                in.stepOut();
                ret = m;
            }
            break;
            case SEXP:
            case LIST: {
                String tname = null;
                for (String s : in.getTypeAnnotations()) {
                    tname = s;
                }
                in.stepIn();
                int hash = tname == null ? -1 : tname.indexOf('#');
                if (hash < 0) {
                    Collection o = null;
                    if (tname != null) {
                        try {
                            o = (Collection) Class.forName(tname).newInstance();
                        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException ex) {
                        }
                    }
                    if (o == null) {
                        o = new ArrayList();
                    }
                    while (in.next() != null) {
                        Object v = readPOJO(in);
                        o.add(v);
                    }
                    ret = o;
                } else {
                    assert tname != null;
                    String npart = tname.substring(0, hash);
                    Object o;
                    int size = (int) Utils.parseLong(tname, hash + 1, tname.length());
                    switch (npart) {
                        case "byte":
                            o = new byte[size];
                            break;
                        case "short":
                            o = new short[size];
                            break;
                        case "char":
                            o = new char[size];
                            break;
                        case "int":
                            o = new int[size];
                            break;
                        case "long":
                            o = new long[size];
                            break;
                        case "float":
                            o = new float[size];
                            break;
                        case "double":
                            o = new double[size];
                            break;
                        case "boolean":
                            o = new boolean[size];
                            break;
                        default: {
                            try {
                                o = Array.newInstance(Class.forName(npart), size);
                            } catch (ClassNotFoundException ex) {
                                o = new Object[size];
                            }
                        }
                    }
                    int slot = 0;
                    while (in.next() != null) {
                        Object v = readPOJO(in);
                        Array.set(o, slot++, v);
                    }
                    ret = o;
                }
                in.stepOut();
            }
            break;
            case NULL:
                ret = null;
                break;
            default:
                ret = null;
                break;
        }
        return ret;
    }

    public static void writePOJO(IonWriter out, long pojo) throws IOException {
        out.writeInt(pojo);
    }

    public static void writePOJO(IonWriter out, double pojo) throws IOException {
        out.writeFloat(pojo);
    }

    public static void writePOJO(IonWriter out, boolean pojo) throws IOException {
        out.writeBool(pojo);
    }

    public static void writePOJO(IonWriter out, CharSequence pojo) throws IOException {
        if (pojo == null) {
            out.writeNull(IonType.STRING);
        } else {
            out.writeString(pojo.toString());
        }
    }

    public static void writePOJO(IonWriter out, byte[] pojo) throws IOException {
        if (pojo == null) {
            out.writeNull();
        } else {
            out.writeBlob(pojo);
        }
    }

    public static void writePOJO(IonWriter out, Map pojo) throws IOException {
        if (pojo == null) {
            out.writeNull(IonType.STRUCT);
            return;
        }
        out.stepIn(IonType.STRUCT);
        pojo.forEach((k, v) -> {
            out.setFieldName(k.toString());
            try {
                writePOJO(out, v);
            } catch (IOException ex) {
                System.out.println("writePOJO error: " + ex);
            }
        });
        out.stepOut();
    }

    public static void writePOJO(IonWriter out, Collection pojo) throws IOException {
        if (pojo == null) {
            out.writeNull(IonType.LIST);
            return;
        }
        out.addTypeAnnotation(pojo.getClass().getName());
        out.stepIn(IonType.LIST);
        pojo.forEach(v -> {
            try {
                writePOJO(out, v);
            } catch (IOException ex) {
                System.out.println("writePOJO error: " + ex);
            }
        });
        out.stepOut();
    }

    public static void writePOJOArray(IonWriter out, Object pojo) throws IOException {
        if (pojo == null) {
            out.writeNull(IonType.LIST);
            return;
        }
        int limit = Array.getLength(pojo);
        out.addTypeAnnotation(pojo.getClass().getComponentType().getName() + '#' + limit);
        out.stepIn(IonType.LIST);
        for (int i = 0; i < limit; i++) {
            writePOJO(out, Array.get(pojo, i));
        }
        out.stepOut();
    }

    public static void writeFinishPOJO(IonWriter out, Map pojo) throws IOException {
        writePOJO(out, pojo);
        out.finish();
    }

    public static void writeFinishPOJO(IonWriter out, Object pojo) throws IOException {
        writePOJO(out, pojo);
        out.finish();
    }

    public static void writePOJO(IonWriter out, Object pojo) throws IOException {
        if (pojo == null) {
            out.writeNull();
            return;
        }
        Class cl = pojo.getClass();
        if (pojo instanceof Number) {
            if (cl == Double.class || cl == Float.class) {
                writePOJO(out, ((Number) pojo).doubleValue());
            } else {
                writePOJO(out, ((Number) pojo).longValue());
            }
        } else if (pojo instanceof Map) {
            writePOJO(out, (Map) pojo);
        } else if (pojo instanceof Collection) {
            writePOJO(out, (Collection) pojo);
        } else if (pojo instanceof CharSequence) {
            writePOJO(out, (CharSequence) pojo);
        } else if (cl == Boolean.class) {
            writePOJO(out, (boolean) pojo);
        } else if (pojo instanceof byte[]) {
            writePOJO(out, (byte[]) pojo);
        } else if (cl.isArray()) {
            writePOJOArray(out, pojo);
        } else {
            writePOJO(out, pojo.toString());
        }
    }

    public static void writePOJO(Path path, Object pojo) {
        try (CommitableFile out = CommitableFile.abandonOnClose(path)) {
            System.out.println("Writing " + path);
            IonWriter w = IonTextWriterBuilder.pretty().build(out);
            writePOJO(w, pojo);
            w.finish();
            out.commit();
        } catch (IOException ex) {
            ex.printStackTrace(System.out);
        }
    }

    public static Object readPOJO(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return readPOJO(readerBuilder.build(in));
        }
    }
}
