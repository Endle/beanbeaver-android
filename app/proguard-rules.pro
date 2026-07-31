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
