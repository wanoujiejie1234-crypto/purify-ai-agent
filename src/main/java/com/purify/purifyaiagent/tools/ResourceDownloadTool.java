package com.purify.purifyaiagent.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;


import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static com.purify.purifyaiagent.constant.FileConstant.DOWNLOAD_DIR;


public class ResourceDownloadTool {

    /** 下载超时。HttpUtil.downloadFile 不传超时是无限等待，模型挑到一个不响应的地址就会挂死整步 */
    private static final int TIMEOUT_MS = 60_000;

    /** 本地文件对外暴露的地址前缀，形如 http://localhost:8123/api */
    private final String baseUrl;

    public ResourceDownloadTool(String baseUrl) {
        //配置里可能带结尾的 /，拼 URL 时先剥掉，否则会拼出 //files/download
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    @Tool(description = "Download a resource from a given url and return its download URL. "
            + "Always include the returned download URL in your final answer so the user can click it.")
    public String downloadResource(@ToolParam(description = "URL of the resource to download") String url,@ToolParam(description = "Name of the file to save download resource")String fileName){
        if (!StringUtils.hasText(url)) {
            return "Error downloading resource: the url is empty. Please provide a valid http(s) url.";
        }

        //文件名由大模型生成（或从 URL 推出来），必须清洗后再落盘：直接拼进路径会被 ../ 写出目录之外
        String safeName = safeFileName(fileName, url);
        String filePath = DOWNLOAD_DIR + "/" + safeName;
        try{
            FileUtil.mkdir(DOWNLOAD_DIR);
        }catch (Exception e){
            return "Error downloading resource: "+e.getMessage();
        }
        String lastError = null;
        for (int i = 0; i < 3; i++) {
            try {
                HttpUtil.downloadFile(url, new File(filePath), TIMEOUT_MS);
                //只回文件名等于把用户堵在本地磁盘上：这里补一条后端可直接打开的链接，
                //否则模型只能转述"下载好了"，用户在浏览器里没有任何可点的东西。
                //落盘用的是解码后的裸文件名，URL 这边要重新编码，否则中文名会对不上
                return "resource downloading successfully:" + filePath
                        + "\nDownload URL: " + baseUrl + "/files/download/"
                        + UriUtils.encodePathSegment(safeName, StandardCharsets.UTF_8);
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }
        return "Error downloading resource: " + lastError;
    }

    /**
     * 把模型给的文件名收成一个安全的裸文件名。
     * 去掉了路径分隔符就等于去掉了路径穿越；名字实在不可用时退回 URL 的最后一段。
     */
    static String safeFileName(String fileName, String url) {
        String name = StringUtils.hasText(fileName) ? fileName.trim() : "";
        //模型有时会把整个 URL 当文件名传进来
        if (name.contains("://")) {
            name = "";
        }
        if (!StringUtils.hasText(name)) {
            name = decode(lastSegmentOf(url));
        }
        name = ToolFileNames.sanitize(name);
        return StringUtils.hasText(name) ? name : "download";
    }

    /** URL 里的文件名通常是百分号编码的，解出来才是人看得懂的名字；解不开就原样用 */
    private static String decode(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    /** 取 URL 路径的最后一段做兜底文件名，顺带丢掉 ?query 和 #fragment */
    private static String lastSegmentOf(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
