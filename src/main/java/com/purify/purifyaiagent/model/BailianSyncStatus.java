package com.purify.purifyaiagent.model;

import java.util.List;

/**
 * 「百炼同步」功能的配置自检结果 —— 这个接口<b>不发任何远程请求</b>，只读本地配置。
 *
 * <p><b>{@code missing} 为什么要列出具体是哪几项，而不是只给一个布尔</b>：
 * 这个卡片是给人主动点开的管理页，不是给模型用的工具。工具没配好应当安静地消失
 * （见 {@code AliyunOssProperties} 的取向），但管理页没配好必须<b>说出来、还说清楚填哪个键</b>，
 * 否则超管只会看到一张卡片不见了，然后来问「功能呢」。
 *
 * <p>{@code categories} 放在这里是有意的：分类值必须与写入端、检索端一字不差，
 * 而前端原本把这三个值硬编码在页面里。多一个硬编码点就多一处会漂移的地方，
 * 所以由后端直出，前端照着渲染。
 *
 * <p><b>它是「分类目录」而不是 yml 里那份内置表</b>（见 {@code KnowledgeCategories}）：
 * 除了内置的那几项，还包含用户自建的类型。所以同步一份文档时，可以直接挑一个
 * 已经建好的自建分类；要建一个新的，则在下拉里选「其他…」自己填一个
 * （那条路不经过这个列表——它由前端加一项、后端在写入时校验并接纳）。
 *
 * <p><b>凭证字段只回显前几位。</b>见 {@code BailianKbController} 的注释——这个接口在管理页上，
 * 而管理页会被截图、会被贴进群里排障，secret 一旦出现在响应里就等于泄露了。
 *
 * @param configured   五项是否齐备。false 时前端显示 {@code missing} 并禁用同步
 * @param indexId      百炼知识库 ID
 * @param indexName    数据面按名字找知识库用的名称（和 indexId 是两套寻址方式）
 * @param accessKeyId  脱敏后的 AK
 * @param workspaceId  脱敏后的业务空间 ID
 * @param missing      还缺哪几项配置，值是 yml 里的键名
 * @param categories   可选的分类值，来自分类目录（内置的 + 库里已经用过的）
 */
public record BailianSyncStatus(boolean configured,
                                String indexId,
                                String indexName,
                                String accessKeyId,
                                String workspaceId,
                                List<String> missing,
                                List<String> categories) {
}
