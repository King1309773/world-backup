package com.rlcmj.worldbackup.core;

import java.util.Locale;
import java.util.Objects;

public final class PortablePaths {
    private static final String WINDOWS_INVALID = "<>:\"|?*";

    private PortablePaths() { }

    public static String normalizeRelativePath(String path) {
        Objects.requireNonNull(path, "path");
        String portable = path.replace('\\', '/');
        if (portable.isEmpty() || portable.startsWith("/")
            || portable.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Path must be portable and relative: " + path);
        }

        String[] segments = portable.split("/", -1);
        for (String segment : segments) {
            validateSegment(segment, path);
        }
        return String.join("/", segments);
    }

    public static String requireSafeBasename(String name) {
        String normalized = normalizeRelativePath(name);
        if (normalized.indexOf('/') >= 0) {
            throw new IllegalArgumentException("Name must not contain directories: " + name);
        }
        return normalized;
    }

    private static void validateSegment(String segment, String path) {
        if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
            throw new IllegalArgumentException("Path contains an unsafe segment: " + path);
        }
        if (segment.endsWith(".") || segment.endsWith(" ")) {
            throw new IllegalArgumentException("Path segment has an unsafe suffix: " + path);
        }
        for (int index = 0; index < segment.length(); index++) {
            char value = segment.charAt(index);
            if (Character.isISOControl(value) || WINDOWS_INVALID.indexOf(value) >= 0) {
                throw new IllegalArgumentException("Path contains an unsafe character: " + path);
            }
        }

        String upper = segment.toUpperCase(Locale.ROOT);
        int extension = upper.indexOf('.');
        String device = extension < 0 ? upper : upper.substring(0, extension);
        if ("CON".equals(device) || "PRN".equals(device) || "AUX".equals(device)
            || "NUL".equals(device) || device.matches("COM[1-9]")
            || device.matches("LPT[1-9]")) {
            throw new IllegalArgumentException("Path uses a reserved device name: " + path);
        }
    }
}
