package com.purify.purifyaiagent.rag.pgvector;

import com.purify.purifyaiagent.config.PgVectorProperties;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把用户问题拆成一组<b>关键词检索用的词</b>。
 *
 * <h2>为什么需要它（这一步不做，整个关键词路就是废的）</h2>
 *
 * <p>问「利拉鲁肽的副作用是什么」，如果直接拿整句去做 {@code content LIKE '%整句%'}，
 * <b>一条都召回不了</b>——切片里不会出现这整句话。而用户真正想命中的是切片里的
 * 「利拉鲁肽」。pg_bigm 提供的是 2-gram <i>索引</i>，它<b>不做分词</b>，
 * 所以「问题 → 检索词」只能在应用侧做。
 *
 * <h2>算法：停用词切段 → 整段短语 + 段内 2-gram</h2>
 *
 * <p>三层各司其职：
 * <ul>
 *   <li><b>停用词切段</b>——用最廉价的方式近似出词边界。「的」「是」「什么」天然就是
 *       边界，而且切点两侧<b>绝不跨段组合</b>，否则会造出「一天鸡蛋」这种假词；</li>
 *   <li><b>整段短语</b>——精度的来源。{@code %利拉鲁肽%} 是真正的「精确匹配」；</li>
 *   <li><b>段内 2-gram</b>——召回的来源。中文词汇以双字为主，2-gram 是没有词典时
 *       对词最好的近似。</li>
 * </ul>
 *
 * <p><b>长段为什么只留 2-gram 而不做滑窗</b>：没有词典就不知道词边界，6 字窗口
 * 大概率跨词（15 字的「高血压患者服用利拉鲁肽注意事项」切出「高血压患者服」
 * 这种东西），要求切片逐字出现这么长一串，命中率接近 0，纯属浪费名额。
 * 而它的 2-gram 里 {@code 血压} {@code 服用} {@code 鲁肽} 都是真词。
 * <b>无词典前提下，2-gram 严格优于长滑窗。</b>
 *
 * <h2>它返回的是「词」不是「模式」</h2>
 *
 * <p>不带 {@code %}、不做转义——那是 SQL 组装的事（见 {@link PgVectorSql#likePattern}）。
 * 分开的好处是测试里能对着裸词断言，不用被转义噪声干扰。
 *
 * <p>无状态、纯函数，可以随便单测。
 */
public class KeywordTermExtractor {

    /** 全角 ASCII 的区间，例：{@code ！} U+FF01 ~ {@code ～} U+FF5E。 */
    private static final char FULL_WIDTH_START = 0xFF01;
    private static final char FULL_WIDTH_END = 0xFF5E;
    private static final char FULL_WIDTH_OFFSET = 0xFEE0;

    /** 全角空格。 */
    private static final char IDEOGRAPHIC_SPACE = 0x3000;

    private final PgVectorProperties.Keyword config;

    /** 归一化之后（小写）的停用词。 */
    private final Set<String> stopWords;

    /** 停用词里最长那个的长度，用来限定最长匹配的起点。 */
    private final int longestStopWord;

    public KeywordTermExtractor(PgVectorProperties.Keyword config) {
        this.config = config;

        // 停用词表来自 yml，人可能写成大写。归一化后的文本是小写的，
        // 所以这里也统一小写——否则「配了 A 却切不掉 a」，而且不报任何错
        Set<String> normalized = new HashSet<>();
        int longest = 0;
        for (String word : config.getStopWords()) {
            if (!StringUtils.hasText(word)) {
                continue;
            }
            String value = normalize(word);
            if (value.isEmpty()) {
                // 全是标点之类的停用词没有意义：切段的第一道（按非内容字符切）
                // 已经把它们切掉了
                continue;
            }
            normalized.add(value);
            longest = Math.max(longest, value.length());
        }
        this.stopWords = Set.copyOf(normalized);
        this.longestStopWord = longest;
    }

    /**
     * 拆出这次检索要用的词。
     *
     * @param question 用户这一轮的原始提问
     * @return 检索词，按「先出现的排前面」排列，最多 {@code max-patterns} 个。
     *         <b>空列表是合法结果</b>，表示这个问题提不出有信息量的词（「你好」「？？？」），
     *         调用方应当据此<b>根本不发这次查询</b>，而不是发一条查不到任何东西的查询
     */
    public List<String> extract(String question) {
        if (!StringUtils.hasText(question)) {
            return List.of();
        }

        Set<String> terms = new LinkedHashSet<>();
        for (String run : splitIntoRuns(normalize(question))) {
            for (String part : splitOnStopWords(run)) {
                collectTerms(part, terms);
            }
        }

        return terms.stream().limit(config.getMaxPatterns()).toList();
    }

    /** 归一段文本拆出它贡献的词。 */
    private void collectTerms(String part, Set<String> terms) {
        int length = part.length();
        if (length < config.getMinTermLength()) {
            // 1 个字组不成 2-gram，也无法作为短语被精确命中
            return;
        }
        if (length <= config.getMaxTermLength()) {
            terms.add(part);
        }
        if (config.isIncludeBigrams()) {
            for (int i = 0; i + 2 <= length; i++) {
                terms.add(part.substring(i, i + 2));
            }
        }
    }

    /**
     * 按「非内容字符」切段。标点、空白、符号都算切割点，它们本身不进结果。
     *
     * <p>这一步只是粗切；真正的词边界近似靠 {@link #splitOnStopWords}。
     */
    private static List<String> splitIntoRuns(String text) {
        List<String> runs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (isContentChar(character)) {
                current.append(character);
            } else {
                flush(current, runs);
            }
        }
        flush(current, runs);
        return runs;
    }

    /**
     * 把停用词当作切割点，把一个段再切成几段。
     *
     * <p><b>是切割点不是删除</b>：切点两侧的字不能跨过来组合。删掉「吃」的话，
     * 「一天吃鸡蛋」会变成「一天鸡蛋」，而这个词在哪都查不到。
     */
    private List<String> splitOnStopWords(String run) {
        if (stopWords.isEmpty()) {
            return List.of(run);
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < run.length()) {
            int matched = matchStopWord(run, i);
            if (matched > 0) {
                flush(current, parts);
                i += matched;
            } else {
                current.append(run.charAt(i));
                i++;
            }
        }
        flush(current, parts);
        return parts;
    }

    /**
     * 从 {@code i} 开始能匹配到的最长停用词的长度；没匹配到返回 0。
     *
     * <p><b>最长优先</b>，否则「一天」会被单字「一」抢先切掉，只剩一个「天」——
     * 而「天」是长度 1 的段，会被直接丢弃，等于什么都没切出来。
     */
    private int matchStopWord(String run, int i) {
        int max = Math.min(longestStopWord, run.length() - i);
        for (int length = max; length >= 1; length--) {
            if (stopWords.contains(run.substring(i, i + length))) {
                return length;
            }
        }
        return 0;
    }

    /**
     * 归一化：全角转半角、ASCII 转小写。
     *
     * <p>转小写只影响问题这一侧——pg_bigm 的匹配<b>区分大小写</b>，所以库里若存的是
     * {@code BMI}、问题写 {@code bmi}，这一路仍然匹配不上（向量那一路会兜住）。
     * 但至少能让「问题侧大小写不一致」不再额外制造不匹配。
     */
    private static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character >= FULL_WIDTH_START && character <= FULL_WIDTH_END) {
                character = (char) (character - FULL_WIDTH_OFFSET);
            } else if (character == IDEOGRAPHIC_SPACE) {
                character = ' ';
            }
            out.append(Character.toLowerCase(character));
        }
        return out.toString();
    }

    /**
     * 算不算「内容字符」：汉字、ASCII 字母、数字。
     *
     * <p><b>这是唯一被保留下来进 SQL 的字符集</b>，所以说它是「内联模式」那道
     * 安全论证的基础（见 {@code keyword.inline-patterns}）：走完这一步，
     * 检索词里不可能有引号、反斜杠、百分号或下划线。
     */
    private static boolean isContentChar(char character) {
        return Character.isDigit(character)
                || (character >= 'a' && character <= 'z')
                || Character.UnicodeScript.of(character) == Character.UnicodeScript.HAN;
    }

    /** 把缓冲区里攒的东西收进列表，空的不收。 */
    private static void flush(StringBuilder buffer, List<String> target) {
        if (!buffer.isEmpty()) {
            target.add(buffer.toString());
            buffer.setLength(0);
        }
    }
}
