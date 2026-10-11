package com.rlcmj.worldbackup.config;

import com.rlcmj.worldbackup.WorldBackup;
import com.rlcmj.worldbackup.core.BackupMode;
import net.minecraftforge.common.config.Config;

@Config(modid = WorldBackup.MOD_ID, name = "worldbackup")
public final class WorldBackupConfig {
    @Config.LangKey("worldbackup.config.enabled")
    public static boolean enabled = true;

    @Config.LangKey("worldbackup.config.scheduled_enabled")
    public static boolean scheduledEnabled = true;

    @Config.LangKey("worldbackup.config.exit_enabled")
    public static boolean exitEnabled = true;

    @Config.LangKey("worldbackup.config.mode")
    public static BackupMode mode = BackupMode.INCREMENTAL;

    @Config.RangeInt(min = 1)
    @Config.LangKey("worldbackup.config.interval_minutes")
    public static int intervalMinutes = 20;

    @Config.RangeInt(min = 1)
    @Config.LangKey("worldbackup.config.max_versions")
    public static int maxVersions = 10;

    @Config.RangeInt(min = 1)
    @Config.LangKey("worldbackup.config.increments_per_base")
    public static int incrementsPerBase = 5;

    @Config.LangKey("worldbackup.config.backup_path")
    public static String backupPath = "backups";

    private WorldBackupConfig() { }
}
