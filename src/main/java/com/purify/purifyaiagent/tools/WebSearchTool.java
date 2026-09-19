package com.purify.purifyaiagent.tools;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.jsoup.Jsoup;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.HashMap;

public class WebSearchTool {
    private static final String SEARCH_API_URL = "https://www.searchapi.io/api/v1/search";
    /** 最多取几条。注意是"最多"——实际条数要跟返回数量取小 */
    private static final int TOP_N = 5;
    private static final int SNIPPET_LIMIT = 300;
    private static final int TIMEOUT_MS = 15_000;

    private final String apiKey;

    /**
     * @param apiKey searchapi.io 的 API Key。<b>不能为空</b>——没有 Key 的搜索工具
     *               每次调用都只会拿回一句鉴权失败，与其把它摆给模型看，不如根本别注册。
     *               这个前提由 {@code ToolConfig} 把关：没配 Key 就不创建这个 Bean。
     *               这里再断言一次，是为了万一哪天条件判断写错，能在启动期就炸出来，
     *               而不是等用户问了个问题才发现搜索一直是坏的。
     */
    public WebSearchTool(String apiKey) {
        Assert.hasText(apiKey, "searchapi.api-key 没有配置，WebSearchTool 不应该被创建");
        this.apiKey = apiKey;
    }
    @Tool(description = "Search for information from Baidu Search Engine. Returns a numbered list of titles, links and snippets.")
    public String searchWeb(@ToolParam(description = "Search query keyword")String query){
        if (!StringUtils.hasText(query)) {
            return "搜索失败：关键词为空。";
        }
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("q",query);
        paramMap.put("api_key",apiKey);
        paramMap.put("engine","baidu");
        try{
            String response = HttpUtil.get(SEARCH_API_URL, paramMap, TIMEOUT_MS);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            JSONArray organicResults = jsonObject.getJSONArray("organic_results");
            if (organicResults == null || organicResults.isEmpty()) {
                return "没有搜到「" + query + "」的结果。建议换个关键词，或直接用网页抓取工具读取目标页面。";
            }
            //这里的 min 不能省：原来写死 subList(0, 5)，结果不足 5 条时直接抛越界，
            //被 catch 吞成一句 "Error searching Baidu"，模型拿到的就是一句没法用的废话
            int count = Math.min(TOP_N, organicResults.size());
            StringBuilder sb = new StringBuilder("「").append(query).append("」的搜索结果：\n");
            for (int i = 0; i < count; i++) {
                JSONObject item = organicResults.getJSONObject(i);
                //返回结构化文本而不是原始 JSON 串：模型读得懂，也不用自己再解析一遍
                sb.append(i + 1).append(". ").append(blankIfNull(item.getStr("title"))).append('\n')
                        .append("   链接：").append(blankIfNull(item.getStr("link"))).append('\n')
                        .append("   摘要：").append(cleanSnippet(item.getStr("snippet"))).append('\n');
            }
            return sb.toString();
        }catch (Exception e){
            return "搜索服务调用失败：" + e.getMessage() + "。可以换个关键词重试，或改用网页抓取工具。";
        }
    }

    /** 搜索接口返回的摘要里带 <em> 这类高亮标签，洗掉再给模型看 */
    private static String cleanSnippet(String snippet) {
        if (!StringUtils.hasText(snippet)) {
            return "（无摘要）";
        }
        String text = Jsoup.parse(snippet).text().replaceAll("\\s+", " ").trim();
        return text.length() <= SNIPPET_LIMIT ? text : text.substring(0, SNIPPET_LIMIT) + "…";
    }

    private static String blankIfNull(String value) {
        return StringUtils.hasText(value) ? value : "（无）";
    }
}
