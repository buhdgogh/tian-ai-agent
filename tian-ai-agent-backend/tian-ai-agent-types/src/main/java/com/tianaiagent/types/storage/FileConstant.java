package com.tianaiagent.types.storage;

/**
 * 描述：FileConstant
 */
public interface FileConstant {
    /** 文件保存目录 **/
    String FILE_SAVE_DIR = java.nio.file.Path.of(System.getenv().getOrDefault("TIAN_DATA_DIR", "tmp"))
            .toAbsolutePath().normalize().toString();
}
