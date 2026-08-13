# Keep UniFFI / JNA (also listed in bbreceiptkit consumer rules).
#
# `{ *; }` rather than a bare -keep on purpose: JNA resolves Structure fields
# reflectively by declared name, and UniFFI's generated UniffiLib is loaded
# through a java.lang.reflect.Proxy by Native.load(). It also sidesteps R8 full
# mode's rule that a bare -keep no longer implies keeping <init>.
-keep class com.sun.jna.** { *; }
-keep class uniffi.** { *; }

# JNA's desktop surface (Native$AWT.getComponentID / getWindowID) references AWT,
# which Android does not have. The -keep above keeps those methods, and R8 8.x
# turns unresolved references into a build failure rather than a warning. These
# four are exactly what R8 reported (see missing_rules.txt) — nothing speculative
# — and they are reachable only from code paths this app never calls.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window

# Line numbers in Play Console stack traces. proguard-android-optimize.txt keeps
# RuntimeVisibleAnnotations (which is what JNA's @Structure.FieldOrder needs) but
# not these, so without them a retraced crash has method names and no lines.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ONNX Runtime's Java bindings (foss flavour, for the DocQuad corner detector).
#
# Its native side resolves Java classes by *name* through JNI FindClass, which
# R8 cannot see, so without this it renames them and the lookups return null.
# The failure is nastier than a missing class: OrtSession$SessionOptions.addNnapi
# fails (we build ORT without NNAPI), the native code tries to raise an
# OrtException, FindClass("ai/onnxruntime/OrtException") returns null, and
# GetMethodID on null is a hard JNI abort — the process dies with no Java stack
# and nothing in the crash buffer. Measured on an SM-X218U: reason=5
# APP CRASH(NATIVE) on every tap of Scan, while assembleFossDebug stayed green
# because debug builds never run R8.
-keep class ai.onnxruntime.** { *; }
