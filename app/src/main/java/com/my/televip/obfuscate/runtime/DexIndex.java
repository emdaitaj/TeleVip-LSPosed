package com.my.televip.obfuscate.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the classes of an APK, and the fields and methods each one declares, straight out of its
 * dex files.
 *
 * Reflection could answer the same questions, but only by loading every candidate class, which costs
 * tens of seconds for an app this size and fails for classes the device cannot link. Parsing is both
 * far quicker and the same thing the shipped shapes were built from, so the two agree exactly.
 *
 * Only what is needed is parsed, and the member lists are read on demand, so the tables stay small.
 */
public final class DexIndex {

    private final List<DexFile> files = new ArrayList<>();
    private final Map<String, Entry> classes = new HashMap<>();

    private DexIndex() {}

    public static DexIndex read(String apkPath) throws IOException {
        DexIndex index = new DexIndex();
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("classes") || !name.endsWith(".dex") || name.indexOf('/') >= 0) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    index.add(new DexFile(readFully(in, (int) Math.max(0, entry.getSize())), index));
                }
            }
        }
        return index;
    }

    private void add(DexFile file) {
        files.add(file);
        file.indexClasses();
    }

    public int size() {
        return classes.size();
    }

    /** Class name to the name of its superclass, or null where there is none. */
    public String superOf(String className) {
        Entry entry = classes.get(className);
        return entry == null ? null : entry.superName;
    }

    public Iterable<String> classNames() {
        return classes.keySet();
    }

    /** {fields declared, methods declared}, both zero for a class with no body. */
    public int[] sizeOf(String className) {
        Entry entry = classes.get(className);
        return entry == null ? null : new int[]{entry.fieldCount, entry.methodCount};
    }

    /**
     * The fields and methods a class declares, each as a name and a type descriptor, in the order
     * the dex lists them.
     */
    public Members membersOf(String className) {
        Entry entry = classes.get(className);
        if (entry == null) return null;
        if (entry.members == null) entry.members = entry.file.readMembers(entry.classDataOff);
        return entry.members;
    }

    public static final class Members {
        public final List<String> fieldNames = new ArrayList<>();
        public final List<String> fieldTypes = new ArrayList<>();
        public final List<String> methodNames = new ArrayList<>();
        public final List<String> methodTypes = new ArrayList<>();
        /** How long each method's code is, in 16-bit units; zero where it has none. */
        public final List<Integer> methodSizes = new ArrayList<>();
    }

    private static final class Entry {
        final DexFile file;
        final String superName;
        final int classDataOff;
        final int fieldCount;
        final int methodCount;
        Members members;

        Entry(DexFile file, String superName, int classDataOff, int fieldCount, int methodCount) {
            this.file = file;
            this.superName = superName;
            this.classDataOff = classDataOff;
            this.fieldCount = fieldCount;
            this.methodCount = methodCount;
        }
    }

    // ------------------------------------------------------------ one dex file

    private static final class DexFile {
        private static final int NO_INDEX = -1;
        private static final int HEADER_STRING_IDS_SIZE = 56;

        private final byte[] data;
        private final DexIndex owner;
        private final int stringIdsOff;
        private final int typeIdsOff;
        private final int typeIdsSize;
        private final int protoIdsOff;
        private final int fieldIdsOff;
        private final int methodIdsOff;
        private final int classDefsOff;
        private final int classDefsSize;
        private final String[] typeCache;

        DexFile(byte[] data, DexIndex owner) {
            this.data = data;
            this.owner = owner;
            this.stringIdsOff = int32(HEADER_STRING_IDS_SIZE + 4);
            this.typeIdsSize = int32(HEADER_STRING_IDS_SIZE + 8);
            this.typeIdsOff = int32(HEADER_STRING_IDS_SIZE + 12);
            this.protoIdsOff = int32(HEADER_STRING_IDS_SIZE + 20);
            this.fieldIdsOff = int32(HEADER_STRING_IDS_SIZE + 28);
            this.methodIdsOff = int32(HEADER_STRING_IDS_SIZE + 36);
            this.classDefsSize = int32(HEADER_STRING_IDS_SIZE + 40);
            this.classDefsOff = int32(HEADER_STRING_IDS_SIZE + 44);
            this.typeCache = new String[Math.max(0, typeIdsSize)];
        }

        void indexClasses() {
            for (int i = 0; i < classDefsSize; i++) {
                int offset = classDefsOff + i * 32;
                String name = className(typeDescriptor(int32(offset)));
                if (name == null) continue;
                String superName = className(typeDescriptor(int32(offset + 8)));
                int classDataOff = int32(offset + 24);
                int fields = 0;
                int methods = 0;
                if (classDataOff > 0 && classDataOff < data.length) {
                    int[] cursor = {classDataOff};
                    fields = uleb128(cursor) + uleb128(cursor);
                    methods = uleb128(cursor) + uleb128(cursor);
                }
                owner.classes.put(name, new Entry(this, superName, classDataOff, fields, methods));
            }
        }

        Members readMembers(int classDataOff) {
            Members members = new Members();
            if (classDataOff <= 0 || classDataOff >= data.length) return members;
            int[] cursor = {classDataOff};
            int staticFields = uleb128(cursor);
            int instanceFields = uleb128(cursor);
            int directMethods = uleb128(cursor);
            int virtualMethods = uleb128(cursor);
            readFields(cursor, staticFields, members);
            readFields(cursor, instanceFields, members);
            readMethods(cursor, directMethods, members);
            readMethods(cursor, virtualMethods, members);
            return members;
        }

        private void readFields(int[] cursor, int count, Members members) {
            int index = 0;
            for (int i = 0; i < count; i++) {
                index += uleb128(cursor);          // field_idx_diff, restarting per group
                uleb128(cursor);                   // access flags
                int offset = fieldIdsOff + index * 8;
                members.fieldTypes.add(typeDescriptor(uint16(offset + 2)));
                members.fieldNames.add(string(int32(offset + 4)));
            }
        }

        private void readMethods(int[] cursor, int count, Members members) {
            int index = 0;
            for (int i = 0; i < count; i++) {
                index += uleb128(cursor);          // method_idx_diff, restarting per group
                uleb128(cursor);                   // access flags
                int codeOff = uleb128(cursor);
                int offset = methodIdsOff + index * 8;
                members.methodTypes.add(protoDescriptor(uint16(offset + 2)));
                members.methodNames.add(string(int32(offset + 4)));
                members.methodSizes.add(codeSize(codeOff));
            }
        }

        /**
         * The length of a method's code, which is what tells two methods of the same shape apart
         * from one build to the next. It sits in the code item, past the register counts.
         */
        private int codeSize(int codeOff) {
            if (codeOff <= 0 || codeOff + 16 > data.length) return 0;
            return int32(codeOff + 12);
        }

        private String protoDescriptor(int protoIndex) {
            int offset = protoIdsOff + protoIndex * 12;
            String returnType = typeDescriptor(int32(offset + 4));
            int parametersOff = int32(offset + 8);
            StringBuilder out = new StringBuilder("(");
            if (parametersOff > 0 && parametersOff < data.length) {
                int count = int32(parametersOff);
                for (int i = 0; i < count; i++) {
                    out.append(typeDescriptor(uint16(parametersOff + 4 + i * 2)));
                }
            }
            return out.append(')').append(returnType == null ? "V" : returnType).toString();
        }

        private String typeDescriptor(int typeIndex) {
            if (typeIndex == NO_INDEX || typeIndex < 0 || typeIndex >= typeIdsSize) return null;
            String cached = typeCache[typeIndex];
            if (cached != null) return cached;
            String descriptor = string(int32(typeIdsOff + typeIndex * 4));
            typeCache[typeIndex] = descriptor;
            return descriptor;
        }

        private String string(int stringIndex) {
            if (stringIndex < 0) return null;
            int offset = int32(stringIdsOff + stringIndex * 4);
            if (offset <= 0 || offset >= data.length) return null;
            int[] cursor = {offset};
            uleb128(cursor);                       // length in UTF-16 units; the bytes end with a zero
            int start = cursor[0];
            int end = start;
            while (end < data.length && data[end] != 0) end++;
            char[] chars = new char[end - start];
            int length = 0;
            for (int i = start; i < end; i++) {
                int b = data[i] & 0xFF;
                if (b >= 0x80) return null;        // descriptors and member names are ASCII
                chars[length++] = (char) b;
            }
            return new String(chars, 0, length);
        }

        private int uleb128(int[] cursor) {
            int result = 0;
            int shift = 0;
            while (cursor[0] < data.length) {
                int b = data[cursor[0]++] & 0xFF;
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }

        private int int32(int offset) {
            if (offset < 0 || offset + 4 > data.length) return NO_INDEX;
            return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                    | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
        }

        private int uint16(int offset) {
            if (offset < 0 || offset + 2 > data.length) return NO_INDEX;
            return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        }
    }

    /** "Lorg/telegram/messenger/MessageObject;" to a dotted name; anything else has no entry. */
    static String className(String descriptor) {
        if (descriptor == null || descriptor.length() < 3
                || descriptor.charAt(0) != 'L' || descriptor.charAt(descriptor.length() - 1) != ';') {
            return null;
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static byte[] readFully(InputStream in, int expected) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected > 0 ? expected : 1 << 20);
        byte[] buffer = new byte[1 << 16];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}
