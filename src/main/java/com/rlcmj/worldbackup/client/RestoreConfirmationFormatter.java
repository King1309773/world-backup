package com.rlcmj.worldbackup.client;

final class RestoreConfirmationFormatter {
    interface Translation {
        String format(String key, Object... arguments);
    }

    private RestoreConfirmationFormatter() { }

    static String format(String time, String worldFolder, Translation translation) {
        return translation.format("gui.worldbackup.confirm_restore.message",
            time, worldFolder);
    }
}
