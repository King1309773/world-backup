package com.rlcmj.worldbackup.client;

import com.rlcmj.worldbackup.core.BackupException;

final class BackupErrorFormatter {
    interface Translation {
        String format(String key, Object... arguments);
    }

    private BackupErrorFormatter() { }

    static String format(Throwable failure, Translation translation) {
        String errorKey = key(failure);
        Object[] errorArguments = arguments(failure);
        if ("worldbackup.error.restore_rollback".equals(errorKey)
                && errorArguments.length == 0) {
            errorKey = "worldbackup.error.restore_rollback_unknown";
        }
        return translation.format(errorKey, errorArguments);
    }

    static Object[] arguments(Throwable failure) {
        BackupException backupFailure = findBackupException(failure);
        if (backupFailure != null) return backupFailure.getArguments();
        return new Object[0];
    }

    static String key(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof OutOfMemoryError) {
                return "worldbackup.error.catalog_too_large";
            }
            if (cursor instanceof BackupException) {
                return ((BackupException) cursor).getTranslationKey();
            }
            cursor = cursor.getCause();
        }
        return "worldbackup.error.restore_swap";
    }

    private static BackupException findBackupException(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof BackupException) return (BackupException) cursor;
            cursor = cursor.getCause();
        }
        return null;
    }
}
