# gomobile-generated JNI entry points and the Java facade must survive shrinking.
-keep class facade.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.andy8401.mygo.car.bridge.** { *; }
