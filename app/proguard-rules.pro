# The app has no reflection-based libraries. Keep line numbers so crash
# reports from users stay readable.
-keepattributes SourceFile,LineNumberTable

# Keep class and method names too: the in-app crash report (CrashLog) is
# read by people, not run through a mapping file. The app is small, so
# renaming saves next to nothing. Unused code is still removed.
-dontobfuscate
