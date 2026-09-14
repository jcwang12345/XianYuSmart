# macOS 裸机 QA（MySQL 复用 Docker）

## 边界

- Java 21、Node 22、Maven、npm、前端构建、后端测试与应用进程均在 macOS 裸机运行。
- MySQL 固定使用 `mysql:5.7.18`，复用隔离卷 `xianyusmart_matrix_handoff_mysql`，仅绑定 `127.0.0.1:13306`。
- QA 应用固定绑定 `127.0.0.1:3000`；不会启动应用 Docker 容器，也不会修改 2000 端口的生产容器。
- 运行所需数据库口令、JWT 与 QA Mock 白名单从已停止的隔离容器 `xianyusmart-matrix-handoff-app` 读取，不写入仓库和日志。

## 命令

```bash
# 完整测试（裸机）
scripts/local-verify.sh

# 类型检查、生产前端构建、后端打包并重启裸机 QA
scripts/native-qa.sh deploy

# 常规启停与状态
scripts/native-qa.sh status
scripts/native-qa.sh restart
scripts/native-qa.sh stop
scripts/native-qa.sh stop-all   # 同时停止隔离 MySQL；不会删除数据卷
scripts/native-qa.sh logs 200
```

首次部署会创建容器 `xianyusmart-native-qa-mysql`。Compose 文件只挂载隔离 QA 卷，并在注释中明确禁止替换为生产数据卷。
