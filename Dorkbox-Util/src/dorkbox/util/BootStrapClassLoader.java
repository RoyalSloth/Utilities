/*
 * Copyright 2016 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.util;

import java.util.Arrays;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;

// http://hg.openjdk.java.net/jdk8u/jdk8u/jdk/file/be698ac28848/src/share/native/java/lang/ClassLoader.c
// http://hg.openjdk.java.net/jdk7/jdk7/hotspot/file/tip/src/share/vm/prims/jvm.cpp

// objdump -T <file> | grep foo
// otool -T <file> | grep foo

/**
 * Gives us the ability to inject bytes directly into java's bootstrap classloader.
 * <p>
 * This COMPLETELY bypass all security checks in the Classloader, as it calls native methods directly via JNA.
 */
@SuppressWarnings({"unused", "WeakerAccess"})
public class BootStrapClassLoader {
    public static final int JNI_VERSION_1_1 = 0x00010001;
    public static final int JNI_VERSION_1_2 = 0x00010002;
    public static final int JNI_VERSION_1_4 = 0x00010004;
    public static final int JNI_VERSION_1_6 = 0x00010006;

    // if we want to change the JNI version, this is how we do it.
    public static int JNI_VERSION = JNI_VERSION_1_4;

    private static JVM libjvm;

    public static
    class JavaVM extends Structure {
        public static
        class ByReference extends JavaVM implements Structure.ByReference {}

        public volatile JNIInvokeInterface.ByReference functions;

        JavaVM() {
        }

        JavaVM(Pointer ptr) {
            useMemory(ptr);
            read();
        }

        @Override
        protected
        List getFieldOrder() {
            //noinspection ArraysAsListWithZeroOrOneArgument
            return Arrays.asList("functions");
        }
    }

    public
    interface JVM extends com.sun.jna.Library {
        void JVM_DefineClass(Pointer env, String name, Object loader, byte[] buffer, int length, Object protectionDomain);
        int JNI_GetCreatedJavaVMs(JavaVM.ByReference[] vmArray, int bufsize, int[] vmCount);
    }

    public static
    class JNIInvokeInterface extends Structure {
        public static
        class ByReference extends JNIInvokeInterface implements Structure.ByReference {}

        public volatile Pointer reserved0;
        public volatile Pointer reserved1;
        public volatile Pointer reserved2;

        public volatile Pointer DestroyJavaVM;
        public volatile Pointer AttachCurrentThread;
        public volatile Pointer DetachCurrentThread;

        public volatile GetEnv GetEnv;
        public volatile Pointer AttachCurrentThreadAsDaemon;

        @Override
        protected
        List getFieldOrder() {
            return Arrays.asList("reserved0",
                                 "reserved1",
                                 "reserved2",
                                 "DestroyJavaVM",
                                 "AttachCurrentThread",
                                 "DetachCurrentThread",
                                 "GetEnv",
                                 "AttachCurrentThreadAsDaemon");
        }

        public
        interface GetEnv extends com.sun.jna.Callback {
            int callback(JavaVM.ByReference vm, PointerByReference penv, int version);
        }
    }

    /**
     * Inject class bytes directly into the bootstrap classloader.
     * <p>
     * This is a VERY DANGEROUS method to use!
     *
     * @param classBytes
     *                 the bytes to inject
     */
    public static
    void defineClass(byte[] classBytes) throws Exception {

        if (libjvm == null) {
            String libName;
            if (OS.isMacOsX()) {
                if (OS.javaVersion < 7) {
                    libName = "JavaVM";
                } else {
                    String javaLocation = System.getProperty("java.home");

                    // have to explicitly specify the JVM library via full path
                    // this is OK, because for java on MacOSX, this is the only location it can exist
                    libName = javaLocation + "/lib/server/libjvm.dylib";
                }
            }
            else {
                libName = "jvm";
            }
            libjvm = (JVM) Native.loadLibrary(libName, JVM.class);
        }

        // get the number of JVM's running
        int[] jvmCount = {100};
        libjvm.JNI_GetCreatedJavaVMs(null, 0, jvmCount);

        // actually get the JVM's
        JavaVM.ByReference[] vms = new JavaVM.ByReference[jvmCount[0]];
        for (int i = 0, vmsLength = vms.length; i < vmsLength; i++) {
            vms[i] = new JavaVM.ByReference();
        }

        // now get the JVM's
        libjvm.JNI_GetCreatedJavaVMs(vms, vms.length, jvmCount);

        Exception exception = null;
        for (int i = 0; i < jvmCount[0]; ++i) {
            JavaVM.ByReference vm = vms[i];
            PointerByReference penv = new PointerByReference();
            vm.functions.GetEnv.callback(vm, penv, BootStrapClassLoader.JNI_VERSION);

            // inject into all JVM's that are started by us (is USUALLY 1, but not always)
            try {
                libjvm.JVM_DefineClass(penv.getValue(), null, null, classBytes, classBytes.length, null);
            } catch (Exception e) {
                exception = e;
            }
        }

        // something failed, just show us THE LAST of the failures
        if (exception != null) {
            throw exception;
        }
    }
}
