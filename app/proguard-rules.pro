# Components Android instantiates by name from the manifest. R8 cannot see these being
# constructed, so without a keep rule it strips or renames them and the app silently has no
# VPN service.
-keep class dev.muto.app.MutoApplication { *; }
-keep class dev.muto.app.MainActivity { *; }
-keep class dev.muto.app.vpn.MutoVpnService { *; }
-keep class dev.muto.app.tile.MutoTileService { *; }
-keep class dev.muto.app.receiver.BootReceiver { *; }
-keep class dev.muto.app.work.BlocklistUpdateWorker { *; }

# These enum constant names are written to storage - RuleAction and FilterReason into Room
# columns, BlockMode into DataStore - and read back by name. If R8 renames the constants, a
# release built today writes names that a release built tomorrow cannot read, and the user's
# rules quietly stop applying after an update. Keeping the names costs a few bytes.
-keepnames enum dev.muto.core.dns.BlockMode
-keepnames enum dev.muto.core.filter.FilterReason
-keepnames enum dev.muto.app.data.db.RuleAction
-keepclassmembers enum dev.muto.core.dns.BlockMode { *; }
-keepclassmembers enum dev.muto.core.filter.FilterReason { *; }
-keepclassmembers enum dev.muto.app.data.db.RuleAction { *; }

# Room reads these by name from generated code.
-keep class dev.muto.app.data.db.** { *; }

# Room, Compose, OkHttp and WorkManager all ship their own consumer rules, so nothing more is
# needed for them.

# wireguard-android ships no consumer rules of its own, so these have to be stated here.
# libwg-go.so resolves its JNI entry points by symbol name, which encodes the Java class and
# method names, and GoBackend$VpnService is instantiated by the system from the merged manifest.
# Renaming any of that leaves the tunnel failing at runtime with an UnsatisfiedLinkError that
# only shows up in a minified build - which is to say, only in release.
-keep class com.wireguard.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
