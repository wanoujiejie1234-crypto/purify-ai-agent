package com.purify.purifyaiagent.tools;

import org.springframework.util.StringUtils;

/**
 * 工具落盘时的文件名清洗。
 *
 * <p><b>为什么必须有这一层：</b>工具的文件名参数是模型填的，而模型会把用户随口说的话
 * 直接搬过来当文件名。用户说「存成 ../../application.yml」，模型就可能原样传进来，
 * 于是 {@code FILE_DIR + "/" + fileName} 拼出来的路径跑到了目录外面——
 * 该写的文件没写，不该覆盖的被覆盖。这不是理论风险，而是「文件名由外部输入决定」时的默认结局。
 *
 * <p>做法是把所有路径分隔符和 Windows 非法字符换掉，再去掉开头的点和空白。
 * 去掉分隔符之后路径就只剩最后一段，{@code ../} 也就无处可逃；
 * 去掉开头的点是为了处理 {@code ..} 这种光有点、没有分隔符的名字。
 *
 * <p>原来这段正则在 {@code PDFGenerationTool}、{@code ResourceDownloadTool} 里各写了一遍，
 * 给 {@code FileOperationTool} 补上就成了第三遍，所以收到这里统一维护。
 * 三个工具对文件名的要求完全一样：一个安全的裸文件名，不含路径。
 */
final class ToolFileNames {

    /** 路径分隔符 + Windows 文件名非法字符，统一换成下划线。 */
    private static final String ILLEGAL_FILE_NAME_CHARS = "[\\\\/:*?\"<>|]";

    private ToolFileNames() {
    }

    /**
     * 清洗成安全的裸文件名。
     *
     * @return 清洗后的名字；输入为空或清洗后什么都不剩时返回空串，由调用方决定怎么兜底
     */
    static String sanitize(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "";
        }
        return fileName.replaceAll(ILLEGAL_FILE_NAME_CHARS, "_")
                .replaceAll("^[.\\s]+", "")
                .trim();
    }
}
