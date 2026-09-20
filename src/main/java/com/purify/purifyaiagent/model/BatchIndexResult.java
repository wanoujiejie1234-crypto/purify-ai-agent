package com.purify.purifyaiagent.model;

import java.util.List;

/**
 * 批量导入的结果：逐份文件的成功与失败。
 *
 * <p><b>部分失败不回滚。</b>批量导入十份、第七份格式不对，不该把前六份一起撤销——
 * 那意味着用户改完那一份之后得从头再传一遍，而已经成功的那六份还会白白多花一次
 * 向量化与重排的钱。所以这里把每一项的结果都记下来，让调用方自己决定下一步。
 *
 * <p>响应体里同时能看到「成功了哪些」和「失败了为什么」，而不是一个笼统的
 * 「3 个文件导入失败」——后者对排查毫无帮助。
 *
 * @param succeeded 导入成功的份数
 * @param failed    导入失败的份数
 * @param items     逐份明细，顺序与上传顺序一致
 */
public record BatchIndexResult(int succeeded, int failed, List<Item> items) {

    /**
     * 一份文件的导入结果。
     *
     * @param filename 文件名
     * @param ok       成功还是失败
     * @param message  成功时是切片数说明，失败时是失败原因。两边都有话说，所以不为 null
     * @param chunks   产生的切片数；失败时为 0
     */
    public record Item(String filename, boolean ok, String message, long chunks) {

        public static Item ok(String filename, long chunks, long characters) {
            return new Item(filename, true,
                    "已入库：%d 个切片，%d 个字符".formatted(chunks, characters), chunks);
        }

        public static Item failed(String filename, String message) {
            return new Item(filename, false, message, 0);
        }
    }
}
