package org.homework.concurrent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Превращает относительные пути из манифеста в настоящие, гарантируя, что результат лежит строго внутри
 * исходной или целевой папки. Нарушение это IllegalArgumentException: оно станет ошибкой одного файла,
 * остальные продолжат копироваться.
 */
public final class PathGuard {

    private final Path sourceRoot;
    private final Path targetRoot;

    public PathGuard(Path sourceDirectory, Path targetDirectory) {
        this.sourceRoot = sourceDirectory.toAbsolutePath().normalize();
        this.targetRoot = targetDirectory.toAbsolutePath().normalize();
    }

    public Path resolveSource(String relative) {
        return resolve(sourceRoot, relative, "источник");
    }

    public Path resolveTarget(String relative) {
        return resolve(targetRoot, relative, "цель");
    }

    /** Только лексическая проверка, без обращений к диску: годится для быстрого поиска дубликатов. */
    public Path lexicalTarget(String relative) {
        return lexical(targetRoot, relative, "цель");
    }

    private static Path resolve(Path root, String relative, String what) {
        Path resolved = lexical(root, relative, what);
        checkRealPath(root, resolved, what, relative);
        return resolved;
    }

    private static Path lexical(Path root, String relative, String what) {
        if (relative.isEmpty()) {
            throw rejected(what, relative, "пустой путь");
        }
        if (relative.indexOf('\0') >= 0) {
            throw rejected(what, relative, "недопустимый символ NUL");
        }

        Path candidate;
        try {
            candidate = Path.of(relative);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(what + " '" + relative + "': недопустимый путь", e);
        }
        // getRoot() != null ловит и C:\x, и C:x, и \x в Windows, а не только isAbsolute()
        if (candidate.isAbsolute() || candidate.getRoot() != null) {
            throw rejected(what, relative, "абсолютные пути запрещены");
        }
        if (File.separatorChar == '\\') {
            // Windows молча отбрасывает хвостовые точки: "a.txt." это тот же файл, что "a.txt",
            // и такие алиасы обходят проверку дубликатов
            for (Path part : candidate) {
                String name = part.toString();
                if (name.endsWith(".") && !name.equals(".") && !name.equals("..")) {
                    throw rejected(what, relative, "имя заканчивается точкой");
                }
            }
        }

        Path resolved = root.resolve(candidate).normalize();
        if (!resolved.startsWith(root)) {
            throw rejected(what, relative, "путь выходит за пределы папки");
        }
        if (resolved.equals(root)) {
            throw rejected(what, relative, "путь указывает на саму папку");
        }
        return resolved;
    }

    /**
     * Защита от символических ссылок: ближайший существующий элемент пути после разрешения ссылок
     * должен остаться внутри папки. Ещё не созданные части пути ссылками быть не могут.
     */
    private static void checkRealPath(Path root, Path path, String what, String relative) {
        try {
            Path existing = path;
            while (existing != null && existing.startsWith(root) && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.startsWith(root)) {
                return;   // под root ещё ничего не существует
            }
            if (!existing.toRealPath().startsWith(root.toRealPath())) {
                throw rejected(what, relative, "путь через символическую ссылку выходит за пределы папки");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(what + " '" + relative + "': не удалось проверить путь: " + e.getMessage(), e);
        }
    }

    private static IllegalArgumentException rejected(String what, String relative, String reason) {
        return new IllegalArgumentException(what + " '" + relative + "': " + reason);
    }


}
