# Evaluation Pipeline

面向学习和诊断的 RAG 黑盒评测服务。它通过 HTTP 调用 `indexing-pipeline`，保存不可变 JSONL 数据集、逐题结果和分阶段指标。

## 环境

- JDK 21+
- MySQL 8，创建独立数据库：`CREATE DATABASE evaluation_pipeline CHARACTER SET utf8mb4;`
- 已启动并准备好活动索引版本的 `indexing-pipeline`

```powershell
$env:EVAL_MYSQL_URL='jdbc:mysql://localhost:3306/evaluation_pipeline?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:EVAL_MYSQL_USERNAME='root'
$env:EVAL_MYSQL_PASSWORD='your-password'
mvn spring-boot:run
```

访问 <http://localhost:8081>。同一时刻只允许一个 Run；应用重启时遗留运行会标记为 `INTERRUPTED`。

## JSONL

每行一个案例，必须提供稳定的源文档哈希和至少一个证据组：

```json
{"id":"case-001","question":"AP 壁挂安装高度是多少？","documentId":"document-uuid","sourceContentHash":"64位SHA-256十六进制值","evidenceGroups":[{"id":"height","alternatives":[{"sourceBlockIds":["stable-block-id"],"pageStart":4,"pageEnd":4,"sectionPath":"安装注意事项","quoteText":"原文"}]}],"expectedGraph":{"requiredEntities":[],"requiredRelations":[]}}
```

评测集通过文件 SHA-256 版本化。修改后重新导入即可生成新 revision，已导入版本不会被编辑。
