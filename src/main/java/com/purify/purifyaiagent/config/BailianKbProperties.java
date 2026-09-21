package com.purify.purifyaiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 百炼管控面配置，对应 {@code purify.rag.bailian.*}。
 *
 * <p>只服务「知识库同步」这一个功能：用一个阿里云 AK/SK 去调管控面接口，
 * 列出云知识库里的文件与切片。它<b>不参与检索</b>——检索走的还是 {@code store}
 * 选出来的那条链路，两者互不影响（百炼挂了照样能问答）。
 *
 * <h2>为什么和 aliyun.oss.* 分开配</h2>
 *
 * <p>两者都是一对阿里云 AK/SK，看起来可以共用，但需要的 RAM 授权完全不同：
 * 这里要的是百炼数据权限（{@code AliyunBailianDataFullAccess}，含 {@code sfm:ChunkList}），
 * 而 OSS 那对只需要对象存储权限。共用一个字段的后果是「给 OSS 换个 AK」会顺手把百炼也换掉，
 * 而两件事的失败表现天差地别（PDF 生成不出来 vs 同步报 403），排查时必然走错方向。
 *
 * <p>如果同一对 AK 恰好两处都够用，照抄一遍即可，代价只有一次复制。
 *
 * <h2>为什么本类上没有 isConfigured()</h2>
 *
 * <p>{@link AliyunOssProperties} 有一个，但这里不能照抄：那个自己就能判断齐不齐，
 * 而这里还要看 {@code purify.rag.workspace-id}（见 {@link #workspaceId} 的回落规则），
 * 判断依据横跨两个配置类。所以「配没配齐、缺哪几项」放在
 * {@code BailianKbSyncService#status()} 里做，那是唯一同时握着两边的位置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "purify.rag.bailian")
public class BailianKbProperties {

    /** 管控面接入点。和 OSS 的 endpoint 一样属于环境信息，不是机密。 */
    private String endpoint = "bailian.cn-beijing.aliyuncs.com";

    /**
     * 知识库 ID，即百炼控制台里那个形如 {@code otoru9xxxx} 的字符串。
     *
     * <p><b>和 {@code purify.rag.index-name} 不是一回事</b>，别互相替代：
     * 那条走的是数据面，用知识库<i>名字</i>去换 pipeline_id；
     * 管控面这两个接口要的是知识库<i>ID</i>（CreateIndex 返回的 Data.Id）。
     * 两者是不是同一个值，只能实调一次才知道——{@code /api/knowledge/bailian/probe}
     * 就是干这个的。
     *
     * <p>留空不会让应用起不来，只在真正调用时报错。
     */
    private String indexId;

    /**
     * 管控面用的业务空间 ID（形如 {@code llm-3z7uw7fwz0vxxxx}）。
     *
     * <p><b>留空时回落到 {@code purify.rag.workspace-id}</b>。单开一个可选项，
     * 是因为「管控面和数据面要的是不是同一个空间 ID」在写这段时还没被证实——
     * 两边都是 {@code llm-xxx} 的形状、文档也说是同一个，但那只是推断。
     * 实测下来一样就让它一直空着；不一样再填，不用改代码。
     */
    private String workspaceId;

    /** 访问凭证 ID。放在 application-local.yml，这个文件是要进仓库的。 */
    private String accessKeyId;

    /** 访问凭证密钥。同上。 */
    private String accessKeySecret;

    /** 文件清单每页几份。百炼那边默认 10，这里给宽一点，少翻几页。 */
    private int pageSize = 20;

    /**
     * 拉切片时每页几片。<b>100 是接口硬上限</b>，别往上调——
     * 调大了不会报错，只会被服务端按 100 截断，而总数对不上很难查。
     */
    private int chunkPageSize = 100;
}
