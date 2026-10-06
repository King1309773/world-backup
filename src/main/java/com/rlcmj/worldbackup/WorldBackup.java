package com.rlcmj.worldbackup;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(
    modid = WorldBackup.MOD_ID,
    name = WorldBackup.NAME,
    version = WorldBackup.VERSION,
    acceptedMinecraftVersions = "[1.12.2]",
    clientSideOnly = true
)
public final class WorldBackup {
    public static final String MOD_ID = "worldbackup";
    public static final String NAME = "World Backup";
    public static final String VERSION = "1.0.0";

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) { }
}
