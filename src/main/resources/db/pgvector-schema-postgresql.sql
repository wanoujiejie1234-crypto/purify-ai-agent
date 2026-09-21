-- 本地向量库表（PostgreSQL + pgvector 版本）
--
-- 什么时候需要手工执行这份脚本？
--   只有把 purify.rag.pgvector.initialize-schema 配成 false 时。
--   默认是 true，由 PgVectorStore 自己建扩展、建表、建索引，不需要你动手。
--
-- 那为什么还要有这份脚本（即为什么可能得关掉自动建表）：
--   PgVectorStore 的自动建表会依次执行
--       CREATE EXTENSION IF NOT EXISTS vector
--       CREATE EXTENSION IF NOT EXISTS hstore        <-- 无条件执行
--       以及 idType 为 uuid 时的 CREATE EXTENSION IF NOT EXISTS "uuid-ossp"
--   应用连库的账号未必有建扩展的权限（托管实例上更常见），hstore 这一句会让应用启动直接失败
--   （permission denied to create extension "hstore"），而报错信息看不出问题出在 hstore。
--   本项目已经用 id-type: text 避开了 uuid-ossp，剩下的 vector 和 hstore 交给有权限的账号
--   执行一次即可。执行完把它配成 false 启动，行的就是这份脚本建出来的表。
--
-- 表结构尽量与 PgVectorStore 的自动建表保持一致（列名、类型都对得上），
-- 唯一多出来的是文件末尾那两个查询用的索引。


-- ============ 1. 扩展 ============
-- pgvector 是向量类型与相似度算子的来源，必须装。
-- 需要较高权限，用有权限的账号执行一次即可；已经装过的话这句是空操作。
CREATE EXTENSION IF NOT EXISTS vector;

-- 确认版本：HNSW 索引需要 pgvector >= 0.5.0，IVFFLAT 需要 >= 0.4.0。
-- 低于 0.5.0 就把下面第 3 段的 USING HNSW 换成 USING IVFFLAT，
-- 或者把 purify.rag.pgvector.index-type 改成 NONE（不建索引，退化成全表扫描）。
SELECT extversion FROM pg_extension WHERE extname = 'vector';


-- ============ 2. 表结构 ============
-- 列名和类型必须与 PgVectorStore 的预期完全一致，少一列、改个名字都会在运行时报错：
--   id        主键，类型由 purify.rag.pgvector.id-type 决定，默认 text
--   content   切片正文
--   metadata  元数据（source / doc_name / title / classification / chunk_index 等）
--   embedding 向量，维度必须与 embedding-model 的真实维度一致
--
-- metadata 用 json 而不是 jsonb，是跟上游保持一致：PgVectorStore 写入时参数上已经带了
-- ?::jsonb 的显式转换，两边类型对齐能少一层隐式转换的疑问。要换成 jsonb 也可以，
-- 但要自己确认写入路径仍然正常。
--
-- 维度对照：text-embedding-v1/v2 → 1536，v3/v4 → 1024。
-- 配错不会在启动时报错，而是写第一条数据时才抛 expected N dimensions, not M；
-- 并且表一旦按旧维度建好，改维度得连表一起重建。
CREATE TABLE IF NOT EXISTS public.rag_knowledge_chunk
(
    id        text PRIMARY KEY,
    content   text,
    metadata  json,
    embedding vector(1024)
);


-- ============ 3. 向量索引 ============
-- 这就是流程图里「建立索引」那一步。HNSW 查询更快、召回更稳，代价是建索引慢一些。
-- 表为空时建索引是瞬间完成的；已经有数据的话这一步会扫描全表，耐心等一下。
CREATE INDEX IF NOT EXISTS rag_knowledge_chunk_hnsw_idx
    ON public.rag_knowledge_chunk USING HNSW (embedding vector_cosine_ops);

-- pgvector < 0.5.0 的库用这条代替上面那条：
-- CREATE INDEX IF NOT EXISTS rag_knowledge_chunk_ivfflat_idx
--     ON public.rag_knowledge_chunk USING IVFFLAT (embedding vector_cosine_ops);


-- ============ 4. 统计与自检用的索引 ============
-- 建索引后的自检、以及 /api/knowledge/stats 都是按元数据字段取值来查的，
-- 走的是普通取值语法（metadata->>'source' = ?），下面的表达式索引能直接命中。
--
-- 注意：这两个索引<b>不</b>加速「按来源删除」。删除走的是 PgVectorStore 内部的
-- `metadata::jsonb @@ '$.source == "xxx"'::jsonpath` 表达式，普通 B-tree 索引用不上，
-- 要加速它得用第 5 段那个 GIN 表达式索引。
CREATE INDEX IF NOT EXISTS rag_knowledge_chunk_source_idx
    ON public.rag_knowledge_chunk ((metadata ->> 'source'));


-- ============ 5. 可选优化（切片上万之后再考虑） ============
-- 检索时的分类过滤（metadata::jsonb @@ '$.classification == "食物热量"'）默认是全表扫
-- 加逐行匹配 jsonpath。数据量大起来之后可以加上表达式 GIN 索引：
--
-- CREATE INDEX IF NOT EXISTS rag_knowledge_chunk_metadata_gin_idx
--     ON public.rag_knowledge_chunk USING GIN ((metadata::jsonb) jsonb_path_ops);
--
-- 加之前先确认它确实被用上，别白建一个索引拖慢写入：
--   EXPLAIN ANALYZE SELECT id FROM public.rag_knowledge_chunk
--     WHERE metadata::jsonb @@ '$.classification == "食物热量"'::jsonpath;
-- 输出里出现 Bitmap Index Scan 就是命中了，仍是 Seq Scan 说明没匹配上。


-- ============ 6. 关键词检索（pg_bigm，可选） ============
-- 新增的那一路「关键词检索」靠这个索引加速 content LIKE '%词%'。
--
-- ⚠ 整段默认注释掉，因为 pg_bigm 是**静态加载**的扩展，装它要动服务端配置：
--     1) 把 pg_bigm 加进 postgresql.conf 的 shared_preload_libraries
--     2) 重启 PostgreSQL            <-- 这一步没法在 SQL 里做，所以放进注释而不是脚本
--     3) 用超级用户执行 CREATE EXTENSION pg_bigm;
--   没做这几步时执行这一段会报 could not open extension control file。
--
--   自建的库直接改 postgresql.conf 即可；托管实例（阿里云 RDS 等）通常不给改这个参数，
--   那就走 6b 的 pg_trgm 退路。
--
--   版本要求：PG 10~15 需内核小版本 >= 20230830，PG 16 不限，PG 17 需 >= 20250830。
--
--   **应用侧是安全的**：启动时会主动探测，探不到就把这一路停用并打一条能照着做的
--   WARN，退化成纯向量检索，不会启动失败。见 PgKeywordSearcher。
--
--   为什么必须「主动探测」而不能「用的时候报错再说」：应用侧那条 SQL 只引用
--   PostgreSQL 核心操作符（LIKE / length / ->>），**不调用任何 pg_bigm 函数**。
--   所以扩展没装时查询**会成功**、只是退化成整表扫描——不探测的话没人会发现。

-- CREATE EXTENSION IF NOT EXISTS pg_bigm;

-- 2-gram GIN 索引。gin_bigm_ops 是 pg_bigm 提供的 operator class，
-- 也是 LIKE '%x%' 能走索引的全部原因 —— btree 对前导通配符完全无用。
--
-- 只建在 content 上：关键词那一路只匹配正文，不匹配元数据。
-- FASTUPDATE 保持默认 on：知识库是「一次写入、多次读取」，把待处理条目留在
-- pending list 里反而更划算。
--
-- 改了 table-name 的话，索引名和 purify.rag.pgvector.keyword.index-name 要一起改
-- （后者留空时会按 <table-name>_bigm_idx 自动推导，所以默认不用管）。
-- CREATE INDEX IF NOT EXISTS rag_knowledge_chunk_bigm_idx
--     ON public.rag_knowledge_chunk USING gin (content gin_bigm_ops);

-- 建完确认它真的被用上（出现 Bitmap Index Scan 才算命中）：
--   EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM public.rag_knowledge_chunk
--     WHERE content LIKE '%利拉鲁肽%';
-- 仍是 Seq Scan 多半是表太小——几百行的表全表扫本来就更快，属正常。
--
-- 若加了索引却仍走 Seq Scan（表不小的情况下），可能是规划器对参数化的
-- LIKE ? 估错了选择率。此时把 purify.rag.pgvector.keyword.inline-patterns
-- 改成 true 再测一次——那会让检索词成为计划期常量。


-- ============ 6b. 兜底：pg_trgm（不需要重启实例） ============
-- 托管实例常常不给改 shared_preload_libraries，那时 pg_bigm 就装不上。
-- pg_trgm 是普通扩展，**装它不用重启**，中文效果差一些但比关掉这一路好。
--
-- 换过去只需要两步 SQL + 两项配置，**Java 侧一行都不用改**
-- （应用发的还是同一个 content LIKE ?）：
--     CREATE EXTENSION IF NOT EXISTS pg_trgm;
--     CREATE INDEX IF NOT EXISTS rag_knowledge_chunk_trgm_idx
--         ON public.rag_knowledge_chunk USING gin (content gin_trgm_ops);
-- 然后改配置：
--     purify.rag.pgvector.keyword.index-name: rag_knowledge_chunk_trgm_idx
--     purify.rag.pgvector.keyword.min-term-length: 3    # 3-gram 对 2 字词提不出 trigram
--
-- 注意 pg_trgm 在 lc_ctype=C 的库上会退化（非字母数字被过滤掉，中文基本上提不出 trigram）。
-- 上之前先确认：SELECT show_trgm('中国'); —— 返回空 {} 就说明这个库用不了。
