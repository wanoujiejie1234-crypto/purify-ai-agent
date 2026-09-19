package com.purify.purifyaiagent.tools;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

public class WebScrapingTool {

    /**
     * 正文截断长度。
     * <p>
     * 原来这里返回的是 {@code doc.html()} —— 整页 HTML 连脚本带样式几万字，
     * 模型要花大量上下文去啃标签，真正有用的正文反而被淹没，
     * 表现出来就是"工具跑了，但返回的内容没用"。
     */
    private static final int MAX_TEXT_LENGTH = 4000;

    @Tool(description = "Scrape the readable text content of a web page. Returns the page title and main text.")
    public String scrapeWebPage(@ToolParam(description = "URL of the web page to scrape")String url){
        if (!StringUtils.hasText(url) || !(url.startsWith("http://") || url.startsWith("https://"))) {
            return "抓取失败：URL 无效，请给出以 http(s):// 开头的完整地址。";
        }
        try{
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; PurifyAgent/1.0)")
                    .timeout(10_000)
                    .maxBodySize(512 * 1024)
                    .get();
            //脚本 / 样式 / 导航占了整页体积的大头，对模型没有任何信息量，先删掉再取正文
            doc.select("script,style,noscript,iframe,nav,footer,header,aside,form,svg").remove();

            String text = doc.body() == null ? "" : doc.body().text().replaceAll("\\s{2,}", " ").trim();
            if (!StringUtils.hasText(text)) {
                //说清是什么情况、接下来能做什么，比丢一句异常给模型强
                return "抓取失败：页面没有可读正文（可能是纯 JS 渲染的页面）。可以换一个 URL，或改用搜索工具。";
            }
            boolean truncated = text.length() > MAX_TEXT_LENGTH;
            if (truncated) {
                text = text.substring(0, MAX_TEXT_LENGTH);
            }
            return "标题：" + doc.title() + "\n正文：" + text
                    + (truncated ? "\n（正文过长，已截断到 " + MAX_TEXT_LENGTH + " 字）" : "");
        }catch(Exception e){
            return "抓取失败：" + e.getClass().getSimpleName() + " - " + e.getMessage()
                    + "。可以换一个 URL，或改用搜索工具试试。";
        }
    }
}
