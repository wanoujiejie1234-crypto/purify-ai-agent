package com.purify.purifyaiagent.tools;

import cn.hutool.core.io.FileUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;

import static com.purify.purifyaiagent.constant.FileConstant.FILE_SAVE_DIR;

/**
 * 让模型能读写本地文件，用来存草稿、把长内容先落盘再看。
 *
 * <p>读写都限定在 {@code tmp/file} 这一个目录里。这个约束靠 {@link ToolFileNames#sanitize}
 * 保证：文件名里的路径分隔符会被换掉，拼接时只剩最后一段，
 * 模型就算传进来 {@code ../../application.yml} 也只会得到一个位于本目录内的怪名字。
 */
public class FileOperationTool {

    /** 文件都放在 tmp 下的 file 子目录，不和下载目录混在一起。 */
    private static final String FILE_DIR = FILE_SAVE_DIR + "/file";

    /** 文件名清洗后什么都不剩时用它，避免拼出一个以 {@code /} 结尾的目录路径。 */
    private static final String DEFAULT_FILE_NAME = "untitled.txt";

    @Tool(description = "读取之前用 writeFile 保存过的文件内容。"
            + "只接受文件名，不要带路径，文件都存放在服务端固定的一个目录里。")
    public String readFile(@ToolParam(description = "文件名，例如 diet-plan.md，不要带路径") String fileName) {
        String path = pathOf(fileName);
        if (!FileUtil.exist(path)) {
            // 说清楚「没有这个文件」和「你能做什么」，比抛一句 Java 异常给模型强：
            // 模型看到 FileNotFound 会原样转述给用户，看到这句则会去换一个名字或先写再读
            return "读取失败：没有找到文件「" + Path.of(path).getFileName() + "」。"
                    + "可以先确认文件名，或者用 writeFile 新建一个。";
        }
        try {
            return FileUtil.readUtf8String(path);
        } catch (Exception exception) {
            return "读取文件失败：" + exception.getMessage();
        }
    }

    @Tool(description = "把内容写成一个文件保存下来，之后可以用 readFile 读回来。"
            + "只接受文件名，不要带路径；同名文件会被直接覆盖。")
    public String writeFile(@ToolParam(description = "文件名，例如 diet-plan.md，不要带路径") String fileName,
                            @ToolParam(description = "要写入的完整内容") String content) {
        String path = pathOf(fileName);
        try {
            // 目录只在第一次写入时创建。放在 try 里是因为它也可能失败——
            // 磁盘满、没权限都会在这一步炸，一起兜住比让它冒到模型那里好
            FileUtil.mkdir(FILE_DIR);
            FileUtil.writeUtf8String(content == null ? "" : content, path);
            return "已写入：" + path + "（" + FileUtil.size(Path.of(path).toFile()) + " 字节）";
        } catch (Exception exception) {
            return "写入文件失败：" + exception.getMessage();
        }
    }

    /**
     * 把模型给的文件名收拾成一个位于 {@link #FILE_DIR} 内的完整路径。
     *
     * <p>清洗去掉了所有路径分隔符，所以拼接结果必然落在这个目录里，
     * 不需要再做一次 {@code normalize + startsWith} 的判断。
     */
    private static String pathOf(String fileName) {
        String safeName = ToolFileNames.sanitize(fileName);
        return FILE_DIR + "/" + (StringUtils.hasText(safeName) ? safeName : DEFAULT_FILE_NAME);
    }
}
