package com.tianaiagent.tools;

import java.nio.file.Path;

final class SafePathResolver {
    private SafePathResolver() {}

    static Path resolve(String baseDirectory, String fileName) {
        if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("文件名不能为空");
        Path base = Path.of(baseDirectory).toAbsolutePath().normalize();
        Path target = base.resolve(fileName).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new IllegalArgumentException("文件路径超出允许目录");
        }
        return target;
    }
}
