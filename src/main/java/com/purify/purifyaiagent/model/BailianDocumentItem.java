package com.purify.purifyaiagent.model;

/**
 * 百炼知识库里的一份文档，外加它在本地向量库里的同步状态——「百炼同步」表格的一行。
 *
 * <p>字段分两组：{@code fileId} 起那一段来自百炼的文件清单，
 * {@code syncState} 起那一段来自本地那次批量查询。两组合并成一行，
 * 是因为用户看这张表时问的就是「百炼有什么、其中哪些我还没搬过来」，
 * 拆成两个接口让前端自己对，只会把这件事推给前端做第二遍。
 *
 * <p><b>这里没有「百炼侧有多少片」。</b>百炼的文件清单接口不返回切片数，
 * 要拿就得逐份去调切片接口（限流 10 QPS）——一页二十行就是二十次远程调用，
 * 换来一个列表页上的数字。所以那个数只有展开某一份时才去取。
 * 谁想「顺手把片数加上」之前，先看这句。
 *
 * @param fileId      百炼侧的文档标识。同步时按它拉切片，也是判断「是不是同一份」的依据
 * @param name        文件名。同时用作本地 {@code source}，所以它也是幂等锚点
 * @param status      百炼侧的导入状态：{@code FINISH} 才有切片可拉，{@code RUNNING} 等还在解析
 * @param size        字节数
 * @param documentType 文档类型，如 {@code md} / {@code pdf}
 * @param gmtModified 百炼侧的修改时间（原始值，不做换算）。判断「百炼那边改过没有」只用它
 * @param syncState   本地同步状态，见 {@link BailianSyncState}
 * @param localChunks 本地这份来源有多少片；没同步过时是 0
 * @param localUploadedAt 本地这份来源的入库时刻；没同步过时是 {@code null}
 */
public record BailianDocumentItem(String fileId,
                                  String name,
                                  String status,
                                  Integer size,
                                  String documentType,
                                  Long gmtModified,
                                  BailianSyncState syncState,
                                  long localChunks,
                                  String localUploadedAt) {
}
