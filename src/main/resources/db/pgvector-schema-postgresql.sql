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
--   阿里云 RDS 的普通账号未必有建扩展的权限，hstore 这一句会让应用启动直接失败
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
