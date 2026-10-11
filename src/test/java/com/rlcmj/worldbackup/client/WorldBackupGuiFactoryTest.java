package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.core.BackupMode;
import net.minecraftforge.fml.client.config.ConfigGuiType;
import net.minecraftforge.fml.client.config.GuiConfigEntries.IConfigEntry;
import net.minecraftforge.fml.client.config.GuiEditArrayEntries.IArrayEntry;
import net.minecraftforge.fml.client.config.IConfigElement;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class WorldBackupGuiFactoryTest {
    private static final Function<String, String> TRANSLATOR =
        key -> "translated:" + key;

    @Test
    public void mapsRawModesToTranslationKeysAndLeavesUnknownValuesRaw() {
        assertEquals("worldbackup.mode.full",
            WorldBackupGuiFactory.modeTranslationKey("FULL"));
        assertEquals("worldbackup.mode.incremental",
            WorldBackupGuiFactory.modeTranslationKey("INCREMENTAL"));
        assertEquals("worldbackup.mode.overwrite",
            WorldBackupGuiFactory.modeTranslationKey("OVERWRITE"));
        assertEquals("FUTURE_MODE",
            WorldBackupGuiFactory.modeTranslationKey("FUTURE_MODE"));
    }

    @Test
    public void decoratesModeDisplayWithoutChangingRawValidValues() {
        RecordingElement raw = new RecordingElement("worldbackup.config.mode",
            new String[] {"FULL", "INCREMENTAL", "OVERWRITE", "FUTURE_MODE"});

        IConfigElement decorated = WorldBackupGuiFactory.decorate(raw, TRANSLATOR);

        assertArrayEquals(raw.validValues, decorated.getValidValues());
        assertArrayEquals(new String[] {
            "translated:worldbackup.mode.full",
            "translated:worldbackup.mode.incremental",
            "translated:worldbackup.mode.overwrite",
            "FUTURE_MODE"
        }, decorated.getValidValuesDisplay());
    }

    @Test
    public void delegatesRawStringAndEnumValuesWhenSettingMode() {
        RecordingElement raw = new RecordingElement("worldbackup.config.mode",
            new String[] {"FULL", "INCREMENTAL", "OVERWRITE"});
        IConfigElement decorated = WorldBackupGuiFactory.decorate(raw, TRANSLATOR);

        decorated.set("INCREMENTAL");
        assertEquals("INCREMENTAL", raw.lastSet);
        decorated.set(BackupMode.OVERWRITE);
        assertSame(BackupMode.OVERWRITE, raw.lastSet);
    }

    @Test
    public void leavesNonModeDisplayValuesUntouched() {
        RecordingElement raw = new RecordingElement("worldbackup.config.backup_path",
            new String[] {"raw"});
        raw.validValuesDisplay = new String[] {"delegate display"};

        IConfigElement decorated = WorldBackupGuiFactory.decorate(raw, TRANSLATOR);

        assertSame(raw.validValuesDisplay, decorated.getValidValuesDisplay());
    }

    @Test
    public void recursivelyDecoratesModeElementsInsideCategories() {
        RecordingElement mode = new RecordingElement("worldbackup.config.mode",
            new String[] {"FULL"});
        RecordingElement category = new RecordingElement("worldbackup.config.category", null);
        category.children = Collections.<IConfigElement>singletonList(mode);

        IConfigElement decorated = WorldBackupGuiFactory.decorate(category, TRANSLATOR);

        assertArrayEquals(new String[] {"translated:worldbackup.mode.full"},
            decorated.getChildElements().get(0).getValidValuesDisplay());
    }

    private static final class RecordingElement implements IConfigElement {
        private final String languageKey;
        private final String[] validValues;
        private String[] validValuesDisplay;
        private Object lastSet;
        private List<IConfigElement> children = Collections.emptyList();

        private RecordingElement(String languageKey, String[] validValues) {
            this.languageKey = languageKey;
            this.validValues = validValues;
            this.validValuesDisplay = validValues;
        }

        @Override public boolean isProperty() { return validValues != null; }
        @Override public Class<? extends IConfigEntry> getConfigEntryClass() { return null; }
        @Override public Class<? extends IArrayEntry> getArrayEntryClass() { return null; }
        @Override public String getName() { return "name"; }
        @Override public String getQualifiedName() { return "qualified"; }
        @Override public String getLanguageKey() { return languageKey; }
        @Override public String getComment() { return "comment"; }
        @Override public List<IConfigElement> getChildElements() { return children; }
        @Override public ConfigGuiType getType() { return ConfigGuiType.STRING; }
        @Override public boolean isList() { return false; }
        @Override public boolean isListLengthFixed() { return false; }
        @Override public int getMaxListLength() { return -1; }
        @Override public boolean isDefault() { return false; }
        @Override public Object getDefault() { return "FULL"; }
        @Override public Object[] getDefaults() { return new Object[] {"FULL"}; }
        @Override public void setToDefault() { }
        @Override public boolean requiresWorldRestart() { return false; }
        @Override public boolean showInGui() { return true; }
        @Override public boolean requiresMcRestart() { return false; }
        @Override public Object get() { return "FULL"; }
        @Override public Object[] getList() { return new Object[0]; }
        @Override public void set(Object value) { lastSet = value; }
        @Override public void set(Object[] values) { lastSet = values; }
        @Override public String[] getValidValues() { return validValues; }
        @Override public String[] getValidValuesDisplay() { return validValuesDisplay; }
        @Override public Object getMinValue() { return null; }
        @Override public Object getMaxValue() { return null; }
        @Override public Pattern getValidationPattern() { return null; }
    }
}
