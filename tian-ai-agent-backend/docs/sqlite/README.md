# SQLite 知识库

默认数据库是后端工作目录下的 `data/knowledge.sqlite`。通过 `spring.datasource.url` 可以指定其它 SQLite 文件，例如 `jdbc:sqlite:D:/Tian/data/knowledge.sqlite`。应用启动时创建目录和表。SQLite 负责知识库空间、文档、解析任务、分块、删除标记和本地向量。工作台业务数据使用单独的 MySQL 数据源。

## 迁移已有知识库

如果有 `data/knowledge.mv.db`，先停止占用它的旧后端，再从后端根目录运行一次迁移。需要 JDK 21、能读取旧库的 H2 JDBC JAR，以及项目使用的 SQLite JDBC JAR。将下方路径替换成 Maven 本地仓库中的实际文件。

```powershell
$legacyDriver = 'D:/maven-repository/com/h2database/h2/2.3.232/h2-2.3.232.jar'
$sqliteDriver = 'D:/maven-repository/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar'
java -cp "$legacyDriver;$sqliteDriver" docs/sqlite/MigrateKnowledge.java data/knowledge.mv.db data/knowledge.sqlite
```

迁移程序以只读方式打开旧库，复制关系记录和向量字节，核对每张表的行数，再发布新文件。目标文件须尚未存在。迁移成功后启动新后端，原文、解析 Markdown、Wiki 版本和 Milvus 数据沿用现有目录和配置。
