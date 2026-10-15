# OpenCV calls into Java from native code
-keep class org.opencv.** { *; }

# PdfBox-Android
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# ZXing
-keep class com.google.zxing.** { *; }
