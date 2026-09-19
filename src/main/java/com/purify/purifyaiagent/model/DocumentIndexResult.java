package com.purify.purifyaiagent.model;

/**
 * 一份文档建完索引后的结果。
 *
 * @param source         文档来源标识（就是幂等删除用的那个名字），后续删/查都用它
 * @param classification 归档到的分类
 * @param chunkCount     切出并写进向量库的切片数
 * @param characterCount 切片的字符数合计，可以用来粗略判断切片有没有把内容切丢
 */
public record DocumentIndexResult(String source, String classification, int chunkCount, long characterCount) {
}
