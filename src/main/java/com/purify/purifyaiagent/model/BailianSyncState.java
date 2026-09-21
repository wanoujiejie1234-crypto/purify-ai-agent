package com.purify.purifyaiagent.model;

/**
 * 一份百炼文档相对本地向量库的同步状态。
 *
 * <p><b>为什么是四态而不是一个布尔</b>：布尔只能回答「在不在」，而这四件事对用户
 * 要做的动作完全不同——
 * <ul>
 *   <li>{@link #MISSING} 没同步过，勾上同步就行；</li>
 *   <li>{@link #SYNCED} 已经是最新的，同步它只是白花钱；</li>
 *   <li>{@link #STALE} 百炼那边改过，本地这份旧了，值得重同步；</li>
 *   <li>{@link #NAME_CONFLICT} <b>本地有一份同名的、但不是从百炼来的</b>——
 *       同步会把它覆盖掉。这是唯一一个「点了会造成破坏」的状态，
 *       所以必须能和「没同步过」分开，前端才能对它单独弹确认框。</li>
 * </ul>
 * 把它们压成布尔，{@code NAME_CONFLICT} 就退化成 {@code MISSING}，
 * 而覆盖本地手传文档这件事会静默发生。
 */
public enum BailianSyncState {

    /** 本地没有这份来源。 */
    MISSING,

    /** 本地有，且确认是从百炼的同一个文件同步来的、之后百炼没再改过。 */
    SYNCED,

    /** 本地有，也确认来自百炼同一个文件，但百炼那边之后又改过。 */
    STALE,

    /** 本地有同名来源，但它<b>不是</b>从百炼来的（或来自另一个百炼文件）。同步会覆盖它。 */
    NAME_CONFLICT
}
