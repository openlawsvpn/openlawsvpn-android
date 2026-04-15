# Keep JNI entry points — called by native code via JNI FindClass/GetMethodID.
# These method names must match the signatures in jni_bridge.cpp exactly.
-keep class com.openlawsvpn.android.VpnConnectionService {
    public int buildTun(java.lang.String);
    public boolean protectSocket(int);
    public void onVpnLog(java.lang.String);
}

# Keep the JNI library object (System.loadLibrary + native declarations).
-keep class com.openlawsvpn.android.jni.LibOpenLawsVpn { *; }

# Keep VpnService subclass — bound by the Android system.
-keep public class com.openlawsvpn.android.VpnConnectionService extends android.net.VpnService { *; }

# Navigation SafeArgs generated classes.
-keep class com.openlawsvpn.android.**Args { *; }
-keep class com.openlawsvpn.android.**Directions { *; }

# Retain source file names and line numbers for useful crash stack traces.
-keepattributes SourceFile,LineNumberTable

# If you upload a mapping file to a crash reporter, rename the attribute.
-renamesourcefileattribute SourceFile
