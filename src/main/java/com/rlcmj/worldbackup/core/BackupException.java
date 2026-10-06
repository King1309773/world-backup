package com.rlcmj.worldbackup.core;

import java.util.Objects;

public class BackupException extends Exception {
    private final String translationKey;
    private final Object[] arguments;

    public BackupException(String translationKey, Object... arguments) {
        this(translationKey, null, arguments);
    }

    public BackupException(String translationKey, Throwable cause, Object... arguments) {
        super(requireTranslationKey(translationKey), cause);
        this.translationKey = translationKey;
        this.arguments = arguments == null ? new Object[0] : arguments.clone();
    }

    private static String requireTranslationKey(String value) {
        Objects.requireNonNull(value, "translationKey");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("translationKey must not be blank");
        }
        return value;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    public Object[] getArguments() {
        return arguments.clone();
    }
}
