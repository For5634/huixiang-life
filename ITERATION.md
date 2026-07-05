# 惠享生活 - 迭代优化路线图

> 当前版本：v1.0（已推送 GitHub：https://github.com/For5634/huixiang-life）

本文档记录基于代码审查发现的改进点，按优先级排列，供后续迭代参考。

## 🔴 高优先级（影响用户体验 / 系统稳定）

### 1. 登录态自动丢失问题
- **现象**：部分场景下用户登录后会自动跳回登录页
- **根因**：
  - `RefreshTokenInterceptor` 通过 ThreadLocal 注入用户，但异步线程（如 `@Async`、消息消费）拿不到用户上下文
  - 前端 `info.html` / `other-info.html` 在 `/user/me` 返回 null 时直接跳转，但接口实际可能只是返回了 `Result.data=null`
- **建议**：
  - 后端：`/user/me` 在 token 有效但 ThreadLocal 没拿到时，主动从 Redis 重新加载用户
  - 前端：区分"无 token" 与"接口返回 null"，无 token 才跳登录
  - 给异步线程加 `TransmittableThreadLocal` 或在 MQ 消费时显式从消息体补用户

### 2. AiController 流式接口编译错误（已有 stub）
- **现象**：langchain4j 的 `TokenStream.onPartialResponse` API 在当前版本不存在
- **位置**：`src/main/java/com/hmdp/controller/AiController.java:89`
- **建议**：参考 langchain4j 1.0+ 的 `StreamingChatLanguageModel` 实际签名重写，或改用 `ChatLanguageModel` 同步响应 + SSE 推送

### 3. ShopTools Bean 注入失败导致全 Spring 上下文起不来
- **现象**：Redis 没启动时 `shopTools` 注入失败，连锁导致整个应用启动失败
- **位置**：`src/main/java/com/hmdp/ai/tools/ShopTools.java`
- **建议**：给 AI Tools 类加 `@ConditionalOnProperty(name = "deepseek.api-key")`，没配 API key 时不注册 Bean，应用仍可独立运行

---

## 🟡 中优先级（代码质量 / 可维护性）

### 4. 测试覆盖不足
- **现状**：仅 `PureUtilsTest`（9 个纯单元测试）能跑通；集成测试依赖 Redis + MySQL
- **建议**：
  - 引入 Testcontainers 启动 Redis/MySQL，CI 自动跑集成测试
  - 给 `VoucherOrderServiceImpl.seckillVoucher` 加并发压测（10 线程 1000 请求验证不超卖）
  - Mock 化 LuckyClient / DeepSeek 调用

### 5. .env 文件已 gitignore 但密码硬编码在 application.yaml
- **检查**：确认 `application.yaml` 中所有敏感字段都走 `${ENV_VAR}` 占位
- **建议**：在 `application.yaml` 顶部加注释，说明所有密钥都从 `.env` 读取

### 6. 前端子模块管理
- **现状**：`nginx-1.18.0/html/hmdp` 之前是独立 git 仓库，已通过 `git rm --cached -f` 强制移除
- **建议**：把前端代码独立推到一个 `huixiang-fe` 仓库，主仓库用 git submodule 引用

---

## 🟢 低优先级（锦上添花）

### 7. README 完善
- 加架构图（mermaid）
- 加快速启动脚本 `start.bat` / `start.sh`（一键启动 Redis + MySQL + 应用）
- 加在线 demo 链接 / 截图

### 8. 监控告警
- Spring Boot Actuator + Prometheus，监控 QPS / 慢查询
- 秒杀防超卖告警：库存扣到 0 时飞书推送

### 9. 前端体验
- 首页 `9.9 元秒杀` 卡片加真实倒计时（`23:58:12` 现在是死数据）
- 笔记点赞动画、骨架屏

---

## 验证方式（v1.0 已完成）

| 测试类型 | 状态 | 命令 |
|---|---|---|
| 纯单元测试 | ✅ 通过 | `mvn test -Dtest=com.hmdp.utils.PureUtilsTest` |
| 编译 | ✅ 通过 | `mvn clean compile` |
| 集成测试 | ⚠️ 需 Redis + MySQL | `mvn test -Dtest=com.hmdp.HuiXiangApplicationTests` |
| GitHub CI | ✅ 已配置 | `.github/workflows/ci.yml` |

---

## 已知技术债

- Lombok 与 IDE 的 javac 处理器冲突（IDE 误报 `getSuccess()` 等不存在，Maven 编译实际通过）
- langchain4j 流式 API 版本兼容性
- 前端 `hmdp` 子目录的 git 子模块管理

---

_最后更新：2026-07-05_
