# SWIG uses JNI name lookup, cached method signatures and director upcalls.
# Preserve the generated binding until its native lookup table is audited alongside
# a smaller SWIG interface. R8 cannot see these references in libpjsua2.so.
-keep class org.pjsip.pjsua2.** { *; }

# SWIG's director connection code also looks up app subclass callback overrides.
-keepclassmembers class * extends org.pjsip.pjsua2.Endpoint { public *** on*(...); }
-keepclassmembers class * extends org.pjsip.pjsua2.Account { public *** on*(...); }
-keepclassmembers class * extends org.pjsip.pjsua2.Call { public *** on*(...); }
-keepclassmembers class * extends org.pjsip.pjsua2.AudioMediaPort { public *** onFrame*(...); }
-keepclassmembers class * extends org.pjsip.pjsua2.LogWriter { public void write(org.pjsip.pjsua2.LogEntry); }
