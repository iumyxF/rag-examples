# Graph RAG Lab

学习型端到端 Graph RAG：Docling 文档解析、Elasticsearch BM25/向量检索、MySQL 知识图谱、RRF、Jina 重排、可验证引用和 Mermaid 图。

## 环境

- JDK 21+
- MySQL 8.0，先创建数据库：`CREATE DATABASE graph_rag CHARACTER SET utf8mb4;`
- Elasticsearch 8.x
- 兼容 docling-serve API 的 Docling HTTP 服务
- OpenAI-compatible Chat 与 Embedding 服务
- Jina API Key

## 配置

至少设置：

```powershell
$env:MYSQL_URL = 'jdbc:mysql://localhost:3306/graph_rag?' +
  'useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:MYSQL_USERNAME='root'
$env:MYSQL_PASSWORD='your-password'
$env:ELASTICSEARCH_URL='http://localhost:9200'
$env:DOCLING_BASE_URL='https://your-docling-service'
$env:DOCLING_AUTH_HEADER_VALUE='your-docling-api-key'
$env:OPENAI_BASE_URL='https://your-openai-compatible/v1'
$env:OPENAI_API_KEY='your-key'
$env:OPENAI_CHAT_MODEL='your-chat-model'
$env:OPENAI_TIMEOUT='120s'
$env:OPENAI_MAX_RETRIES='0'
$env:OPENAI_ENABLE_THINKING='false'
$env:OPENAI_MAX_COMPLETION_TOKENS='4096'
$env:EMBEDDING_MODEL='text-embedding-v4'
$env:EMBEDDING_DIMENSION='1536'
$env:JINA_API_KEY='your-jina-key'
```

`qwen3.8-flash` 默认开启深度思考。索引管线中的图谱抽取、查询理解和答案生成均使用
结构化 JSON 输出，默认关闭思考模式并限制输出长度，以避免同步非流式请求长时间无响应。
如替换为确实需要推理的兼容模型，可通过 `OPENAI_ENABLE_THINKING=true` 重新开启。

当前索引管线只对 Docling 提取出的文本生成向量，并通过 OpenAI-compatible
`/embeddings` 接口调用模型，因此应使用 `text-embedding-v4` 等文本 Embedding 模型。
`qwen3-vl-embedding` 使用阿里云多模态 API 的 `input.contents` 请求格式，不能直接替换到
这个接口中，否则可能返回 `url error`。修改模型或向量维度后，需要删除并重建
Elasticsearch 中的 `rag_chunk_v1` 索引。

第三方 Docling 鉴权使用 docling Java Client 支持的 API Key 机制。若服务使用非标准自定义 Header，需要在网关侧转换为 `Authorization: Bearer ...`。

## 启动

本次七步流水线不兼容旧数据。升级后先停止应用，并执行一次：

```powershell
mysql -u root -p graph_rag < src/main/resources/reset.sql
curl.exe -X DELETE http://localhost:9200/rag_chunk_v1
Remove-Item -LiteralPath '.\data\uploads' -Recurse -Force
```

确认 `RAG_UPLOAD_DIRECTORY` 后再清理对应目录；上述路径仅适用于默认配置。随后启动应用，
`schema.sql` 会创建新表，Elasticsearch 索引也会自动重建。不要把 `reset.sql` 配置为启动脚本。

```powershell
mvn spring-boot:run
```

访问 <http://localhost:8080>。首次启动会执行 `schema.sql` 并自动创建 `rag_chunk_v1` 索引。Embedding 模型或维度变化后需删除并重建该索引。

## API

- `POST /api/documents` multipart 上传
- `GET /api/documents`
- `GET /api/documents/{id}`
- `POST /api/documents/{id}/pipeline/{step}`
- `GET /api/documents/{id}/pipeline/{step}/preview`
- `DELETE /api/documents/{id}/working-version`
- `DELETE /api/documents/{id}`
- `GET /api/documents/{id}/graph`
- `POST /api/rag/query`
- `POST /api/internal/evaluation/v1/query`

`step` 可取 `docling`、`chunking`、`embedding`、`elasticsearch`、`graph`、`activate`。
上传仅保存原文件，后续步骤需要在网页或 API 中逐个同步执行。

评测接口返回 BM25、向量、图、融合和重排阶段的稳定来源轨迹，并支持
`generateAnswer=false` 跳过答案生成。稳定来源字段需要重新执行 Docling、分片和索引步骤；
已有 `rag_chunk_v1` 数据不会自动补齐这些字段。
