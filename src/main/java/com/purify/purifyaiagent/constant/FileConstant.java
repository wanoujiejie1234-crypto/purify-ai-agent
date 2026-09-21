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

    /**
     * 用户头像落在哪里。
     *
     * <p>和下载目录分开，是因为两者对外的语义完全不同：下载目录里是**别人**的东西
     * （工具抓回来的文件），头像目录里是**本人的**上传。混在一起的话，
     * 清理下载目录时会把头像一起删掉，而「头像莫名其妙没了」很难联想到是一次文件清理。
     *
     * <p>同样被 StaticResourceConfig 挂到 /files/avatar/** 上对外提供。
     */
    String AVATAR_DIR = FILE_SAVE_DIR + "/avatar";

    /**
     * {@code FileOperationTool} 读写文本文件的地方。
     *
     * <p><b>这个目录没有被挂到 HTTP 上，而且是有意的</b>（理由见 {@code StaticResourceConfig}）：
     * 里面是模型自己写出来的中间产物，不该随随便便对外暴露。
     * 资料库要提供下载时，走的是带鉴权的 {@code GET /api/resources/{id}/download}，
     * 而不是给它加一条静态映射。
     *
     * <p>放在这里而不是留在 {@code FileOperationTool} 里当私有常量，是因为
     * 资料库那边删除文件时要知道它——两处各写一份，改了一处就会出现
     * 「记录删了但文件还在」这种不报错的问题。
     */
    String WORK_FILE_DIR = FILE_SAVE_DIR + "/file";
}
