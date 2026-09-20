# Room generates implementations reflectively at build time; nothing extra is needed for it.
# Keep the VpnService entry point and the tile/receiver components the system instantiates by name.
-keep class dev.muto.app.vpn.MutoVpnService { *; }
-keep class dev.muto.app.tile.MutoTileService { *; }
-keep class dev.muto.app.receiver.BootReceiver { *; }
