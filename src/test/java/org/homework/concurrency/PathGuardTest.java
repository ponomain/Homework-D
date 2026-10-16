package org.homework.concurrency;

import org.homework.concurrent.PathGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class PathGuardTest {

    @TempDir
    Path dir;

    private Path sourceRoot;
    private Path targetRoot;
    private PathGuard guard;

    @BeforeEach
    void setUp() throws IOException {
        sourceRoot = Files.createDirectory(dir.resolve("src"));
        targetRoot = dir.resolve("dst");   // намеренно ещё не создана: copier создаёт её сам
        guard = new PathGuard(sourceRoot, targetRoot);
    }

    @Test
    void resolvesInsideBothRoots() {
        assertEquals(sourceRoot.toAbsolutePath().normalize().resolve("photos/001.jpg"),
                guard.resolveSource("photos/001.jpg"));
        assertEquals(targetRoot.toAbsolutePath().normalize().resolve("images/001.jpg"),
                guard.resolveTarget("images/001.jpg"));
    }

    @Test
    void allowsDotDotThatStaysInside() {
        assertEquals(sourceRoot.toAbsolutePath().normalize().resolve("b.txt"), guard.resolveSource("a/../b.txt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "../x", "a/../../x", "a/..", "a/../.."})
    void rejectsPathsOutsideOrOnTheRoot(String relative) {
        assertThrows(IllegalArgumentException.class, () -> guard.resolveSource(relative));
        assertThrows(IllegalArgumentException.class, () -> guard.resolveTarget(relative));
    }

    @Test
    void rejectsAbsolutePath() {
        String absolute = sourceRoot.resolve("x.txt").toAbsolutePath().toString();
        assertThrows(IllegalArgumentException.class, () -> guard.resolveSource(absolute));
        assertThrows(IllegalArgumentException.class, () -> guard.resolveTarget(absolute));
    }

    @Test
    void rejectsNulCharacter() {
        assertThrows(IllegalArgumentException.class, () -> guard.resolveSource("a\0b"));
    }

    @Test
    void rejectsWindowsDriveAndRootedPaths() {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "только для Windows");
        for (String path : List.of("C:\\evil.bin", "C:evil.bin", "\\evil.bin", "\\\\server\\share\\x")) {
            assertThrows(IllegalArgumentException.class, () -> guard.resolveSource(path), path);
            assertThrows(IllegalArgumentException.class, () -> guard.resolveTarget(path), path);
        }
    }

    @Test
    void rejectsSymlinkLeadingOutsideSource() throws IOException {
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "x");
        createLinkOrSkip(sourceRoot.resolve("link"), outside);

        assertThrows(IllegalArgumentException.class, () -> guard.resolveSource("link/secret.txt"));
    }

    @Test
    void rejectsSymlinkLeadingOutsideTarget() throws IOException {
        Path outside = Files.createDirectory(dir.resolve("outside"));
        Files.createDirectory(targetRoot);
        createLinkOrSkip(targetRoot.resolve("link"), outside);

        assertThrows(IllegalArgumentException.class, () -> guard.resolveTarget("link/new.bin"));
    }

    @Test
    void allowsNotYetExistingTargetUnderMissingRoot() {
        assertEquals(targetRoot.toAbsolutePath().normalize().resolve("a/b/c.bin"), guard.resolveTarget("a/b/c.bin"));
    }

    private static void createLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "символические ссылки недоступны: " + e);
        }
    }
}
