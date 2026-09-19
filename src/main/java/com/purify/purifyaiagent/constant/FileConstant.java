package com.purify.purifyaiagent.constant;

/**
 * 文件常量
 *
 */
public interface FileConstant {
    String FILE_SAVE_DIR = System.getProperty("user.dir")+"/tmp";

    /**
     * 下载工具把文件落在哪里。
     * 这个目录同时被 StaticResourceConfig 挂到 /files/download/** 上对外提供，
     * 两处必须同源，所以在这里定死，不要各写各的。
     */
    String DOWNLOAD_DIR = FILE_SAVE_DIR + "/download";
}
