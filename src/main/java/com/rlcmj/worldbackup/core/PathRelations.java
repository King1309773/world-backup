package com.rlcmj.worldbackup.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PathRelations {
    private PathRelations() { }

    public static boolean overlaps(Path first, Path second) throws IOException {
        Path resolvedFirst = resolveExistingSegments(first);
        Path resolvedSecond = resolveExistingSegments(second);
        return resolvedFirst.startsWith(resolvedSecond)
            || resolvedSecond.startsWith(resolvedFirst);
    }

    public static Path resolveExistingSegments(Path path) throws IOException {
        Path absolute = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        Path cursor = absolute;
        List<Path> missing = new ArrayList<Path>();
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            Path name = cursor.getFileName();
            if (name != null) missing.add(name);
            cursor = cursor.getParent();
        }
        if (cursor == null) return absolute;
        Path resolved = cursor.toRealPath();
        Collections.reverse(missing);
        for (Path name : missing) resolved = resolved.resolve(name);
        return resolved.normalize();
    }
}
