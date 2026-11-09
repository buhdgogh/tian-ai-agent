package com.tianaiagent.infrastructure.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafePathResolverTest {
    @TempDir Path tempDir;

    @Test
    void acceptsChildAndRejectsTraversal() {
        assertEquals(tempDir.resolve("report.txt").normalize(),
                SafePathResolver.resolve(tempDir.toString(), "report.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> SafePathResolver.resolve(tempDir.toString(), "../secret.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> SafePathResolver.resolve(tempDir.toString(), "..\\secret.txt"));
    }
}
