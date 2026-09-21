package com.purify.purifyaiagent.tools;

import cn.hutool.core.io.FileUtil;
import com.purify.purifyaiagent.constant.FileConstant;
import com.purify.purifyaiagent.resource.ResourceKind;
import com.purify.purifyaiagent.resource.ResourceRecorder;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * 让模型能读写本地文件，用来存草稿、把长内容先落盘再看。
 *
 * <p>读写都限定在 {@code tmp/file} 这一个目录里。这个约束靠 {@link ToolFileNames#sanitize}
 * 保证：文件名里的路径分隔符会被换掉，拼接时只剩最后一段，
 * 模型就算传进来 {@code ../../application.yml} 也只会得到一个位于本目录内的怪名字。
 */
public class FileOperationTool {

    /**
     * 文件都放在 tmp 下的 file 子目录，不和下载目录混在一起。
     *
     * <p>常量定义搬到了 {@link FileConstant#WORK_FILE_DIR}：资料库那边删除文件时
     * 也要知道这个目录，两处各写一份的话，改了一处就会出现「记录删了但文件还留着」
     * 这种不报错的问题。
     */
    private static final String FILE_DIR = FileConstant.WORK_FILE_DIR;

    /** 文件名清洗后什么都不剩时用它，避免拼出一个以 {@code /} 结尾的目录路径。 */
    private static final String DEFAULT_FILE_NAME = "untitled.txt";

    /** 写好之后往资料库记一笔。见 {@code ResourceRecorder}——它不抛异常，不会拖累本工具。 */
    private final ResourceRecorder resourceRecorder;

    public FileOperationTool(ResourceRecorder resourceRecorder) {
        this.resourceRecorder = resourceRecorder;
    }

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
            + "只接受文件名，不要带路径；同名文件会被直接覆盖。"
            + "写出来之后用户可以在这里的「资料库」里找到它。")
    public String writeFile(@ToolParam(description = "文件名，例如 diet-plan.md，不要带路径") String fileName,
                            @ToolParam(description = "要写入的完整内容") String content,
                            ToolContext toolContext) {
        String path = pathOf(fileName);
        try {
            // 目录只在第一次写入时创建。放在 try 里是因为它也可能失败——
            // 磁盘满、没权限都会在这一步炸，一起兜住比让它冒到模型那里好
            FileUtil.mkdir(FILE_DIR);
            FileUtil.writeUtf8String(content == null ? "" : content, path);

            long size = FileUtil.size(Path.of(path).toFile());
            // url 传 null：这个目录故意没有对外映射（见 FileConstant#WORK_FILE_DIR），
            // 所以界面上那一条会走带鉴权的下载接口，而不是一个裸链接
            resourceRecorder.record(toolContext, ResourceKind.WRITTEN,
                    Path.of(path).getFileName().toString(),
                    null, Path.of(path).getFileName().toString(), size, null, null);

            return "已写入：" + path + "（" + size + " 字节）";
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
