package com.purify.purifyaiagent.rag;

import com.purify.purifyaiagent.config.RagProperties;

import java.util.List;

/**
 * 分类目录：回答「现在有哪些分类」。
 *
 * <p><b>为什么要有这一层，而不是继续直接读 {@code ragProperties.getRouter().getCategories()}</b>：
 * 分类原本只可能来自 yml 里的 {@code purify.rag.router.categories}，所以「配置里那一份」
 * 就是权威答案，四处直接读它没有任何问题。开放「自定义类型」之后，
 * 分类有了<b>第二个来源</b>——用户在知识库管理页上自己填的那个名字。
 * 而需要「全部分类」的地方有四处，语义各不相同：
 * <ul>
 *   <li>{@link KnowledgeRouter} —— 拿关键词判断一个问题该查哪一类；</li>
 *   <li>{@code ClassificationFilter} —— 判断路由给出的分类认不认识，
 *       不认识就退回全库（见那里的注释：宁可多带噪声，也不要查一个查不到东西的条件）；</li>
 *   <li>{@code KnowledgeBaseController} —— 管理页那个下拉框渲染什么；</li>
 *   <li>{@code BailianKbSyncService} —— 同步卡片上「这份文档归哪一类」的候选值。</li>
 * </ul>
 * 四处各拼一次「配置 ∪ 库里发现的」的话，漂移的表现是「下拉框里有的分类，
 * 路由不认识」——文档传进去了、也显示在列表里，就是从问答里检索不到，
 * 而且不报任何错。所以合并规则只写在这一层。
 *
 * <p><b>顺序是有意义的</b>：{@link #all()} 里内置分类在前、库中发现的在后。
 * 它同时是「一个问题同时命中多个分类时」拼装材料的顺序，也是管理页下拉框的显示顺序——
 * 内置那三个永远排在最前面，用户自己加的排在后面，位置稳定。
 */
public interface KnowledgeCategories {

    /**
     * 全部可用分类，含各自的关键词。
     *
     * <p>内置分类带的是 yml 里那份关键词表；从库里发现的分类，关键词就是<b>它自己的名字</b>
     * ——用户只填了一个类型名，而路由是纯字符串匹配（见 {@link KnowledgeRouter}），
     * 拿名字当关键词是唯一不引入额外配置、也不产生任何远程开销的做法。
     * 代价写在那边的类注释里。
     */
    List<RagProperties.Category> all();

    /** 全部分类的取值，顺序同 {@link #all()}。给下拉框和同步卡片用。 */
    List<String> values();

    /** 这个分类值认不认识。 */
    boolean isKnown(String value);

    /**
     * 目录可能变了（刚入库、刚删干净）时调一下。
     *
     * <p>只有「从库里发现分类」的那份实现需要做事，纯静态的那份是空实现——
     * 所以这里有默认实现，免得每个实现都写一个空方法。
     */
    default void refresh() {
    }
}
