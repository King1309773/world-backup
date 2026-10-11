package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.WorldBackup;
import com.rlcmj.worldbackup.config.WorldBackupConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.ConfigGuiType;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiConfigEntries.IConfigEntry;
import net.minecraftforge.fml.client.config.GuiEditArrayEntries.IArrayEntry;
import net.minecraftforge.fml.client.config.IConfigElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class WorldBackupGuiFactory implements IModGuiFactory {
    @Override
    public void initialize(Minecraft minecraftInstance) { }

    @Override
    public boolean hasConfigGui() {
        return true;
    }

    @Override
    public GuiScreen createConfigGui(GuiScreen parentScreen) {
        String title = I18n.format("worldbackup.config.title");
        List<IConfigElement> elements = ConfigElement.from(WorldBackupConfig.class)
            .getChildElements();
        elements.sort(Comparator.comparing(
            element -> I18n.format(element.getLanguageKey())));
        return new GuiConfig(parentScreen,
            decorate(elements, key -> I18n.format(key)), WorldBackup.MOD_ID,
            false, false, title);
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return Collections.emptySet();
    }

    static List<IConfigElement> decorate(List<IConfigElement> elements,
            Function<String, String> translator) {
        List<IConfigElement> decorated = new ArrayList<IConfigElement>(elements.size());
        for (IConfigElement element : elements) {
            decorated.add(decorate(element, translator));
        }
        return decorated;
    }

    static IConfigElement decorate(IConfigElement element,
            Function<String, String> translator) {
        return new LocalizedConfigElement(element, translator);
    }

    static String modeTranslationKey(String rawValue) {
        if ("FULL".equals(rawValue)) {
            return "worldbackup.mode.full";
        }
        if ("INCREMENTAL".equals(rawValue)) {
            return "worldbackup.mode.incremental";
        }
        if ("OVERWRITE".equals(rawValue)) {
            return "worldbackup.mode.overwrite";
        }
        return rawValue;
    }

    private static final class LocalizedConfigElement implements IConfigElement {
        private final IConfigElement delegate;
        private final Function<String, String> translator;

        private LocalizedConfigElement(IConfigElement delegate,
                Function<String, String> translator) {
            this.delegate = delegate;
            this.translator = translator;
        }

        @Override public boolean isProperty() { return delegate.isProperty(); }
        @Override public Class<? extends IConfigEntry> getConfigEntryClass() {
            return delegate.getConfigEntryClass();
        }
        @Override public Class<? extends IArrayEntry> getArrayEntryClass() {
            return delegate.getArrayEntryClass();
        }
        @Override public String getName() { return delegate.getName(); }
        @Override public String getQualifiedName() { return delegate.getQualifiedName(); }
        @Override public String getLanguageKey() { return delegate.getLanguageKey(); }
        @Override public String getComment() { return delegate.getComment(); }
        @Override public List<IConfigElement> getChildElements() {
            List<IConfigElement> children = delegate.getChildElements();
            return children == null ? null : decorate(children, translator);
        }
        @Override public ConfigGuiType getType() { return delegate.getType(); }
        @Override public boolean isList() { return delegate.isList(); }
        @Override public boolean isListLengthFixed() { return delegate.isListLengthFixed(); }
        @Override public int getMaxListLength() { return delegate.getMaxListLength(); }
        @Override public boolean isDefault() { return delegate.isDefault(); }
        @Override public Object getDefault() { return delegate.getDefault(); }
        @Override public Object[] getDefaults() { return delegate.getDefaults(); }
        @Override public void setToDefault() { delegate.setToDefault(); }
        @Override public boolean requiresWorldRestart() {
            return delegate.requiresWorldRestart();
        }
        @Override public boolean showInGui() { return delegate.showInGui(); }
        @Override public boolean requiresMcRestart() { return delegate.requiresMcRestart(); }
        @Override public Object get() { return delegate.get(); }
        @Override public Object[] getList() { return delegate.getList(); }
        @Override public void set(Object value) { delegate.set(value); }
        @Override public void set(Object[] values) { delegate.set(values); }
        @Override public String[] getValidValues() { return delegate.getValidValues(); }
        @Override public String[] getValidValuesDisplay() {
            if (!"worldbackup.config.mode".equals(delegate.getLanguageKey())) {
                return delegate.getValidValuesDisplay();
            }
            String[] values = delegate.getValidValues();
            if (values == null) {
                return delegate.getValidValuesDisplay();
            }
            String[] display = new String[values.length];
            for (int index = 0; index < values.length; index++) {
                String key = modeTranslationKey(values[index]);
                display[index] = key.equals(values[index])
                    ? values[index] : translator.apply(key);
            }
            return display;
        }
        @Override public Object getMinValue() { return delegate.getMinValue(); }
        @Override public Object getMaxValue() { return delegate.getMaxValue(); }
        @Override public Pattern getValidationPattern() {
            return delegate.getValidationPattern();
        }
        @Override public boolean hasSlidingControl() { return delegate.hasSlidingControl(); }
    }
}
