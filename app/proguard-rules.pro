# Release builds are shrunk and optimised but NOT obfuscated: this app is installed by hand,
# and its log file (and the Test button's error text) must show real class names and line
# numbers, not "q40 @cf1.a:5".
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
