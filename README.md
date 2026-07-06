# 惠享生活 · 本地生活优惠与智能服务平台

> 简化版大众点评（仿 Dianping）
>
> 基于 Spring Boot 3.2 + Java 17 的移动端 H5 全栈项目

---

## 目录

- [项目概述](#项目概述)
- [快速开始](#快速开始)
- [核心功能清单](#核心功能清单)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [数据库设计](#数据库设计)
- [功能模块详解](#功能模块详解)
- [待优化与已知问题](#待优化与已知问题)
- [项目部署](#项目部署)

---

## 项目概述

**惠享生活** 是一个本地生活优惠与探店点评平台，覆盖 **成都 + 杭州** 两座城市。

用户可以浏览热门探店笔记、按分类搜索商户和优惠券、参与限时秒杀、查看店铺详情和网友评价。平台提供短信验证码登录、用户编辑资料、智能客服（AI Assistant）、签到打卡、好友关注与 Feed 推送、模拟支付等完整功能。

### 数据概况

| 维度 | 数量 |
|------|------|
| 城市 | 成都（10 家店）+ 杭州（14 家店） |
| 探店笔记 | 60+ 篇（含真实图片 41 张 UUID 图片） |
| 网友评价/评论 | 100+ 条 |
| 秒杀券 | 5 张（库存 200/100/150/80/120 份） |
| 用户 | 50+（含 8 个测试达人账号） |
| 订单 | 7 条（已支付 + 未支付 + 已关单） |

---

## 快速开始

### 前置条件

| 组件 | 版本要求 |
|------|----------|
| Java | 17+ |
| Redis | 6.2+（需 GEOSEARCH 命令） |
| RabbitMQ | 3.9+ |
| MySQL | 8.0+（utf8mb4） |
| Nginx | 1.18+ |
| Maven | 3.8+ |

### 启动步骤

```bash
# 1. 初始化数据库
mysql -uroot -p < src/main/resources/db/hmdp.sql
mysql -uroot -p < src/main/resources/db/migration.sql
mysql -uroot -p < src/main/resources/db/chengdu-seed.sql
mysql -uroot -p < src/main/resources/db/mega_seed.sql
mysql -uroot -p < src/main/resources/db/more_seed_data.sql

# 2. 配置环境变量（.env 文件设置后加载）
Get-Content .env | ForEach-Object {
    if ($_ -match '^([^#].+?)=(.+)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}

# 3. 启动后端
mvn spring-boot:run

# 4. 启动前端
nginx-1.18.0/nginx.exe

# 5. 访问
http://localhost:8080
```

### 测试账号

| 手机号 | 昵称 | 备注 |
|--------|------|------|
| `13686869696` | 小鱼同学 | 有笔记、有订单、有粉丝 |
| `13900000001` | 王师傅探店 | 5 篇笔记、4 笔订单 |
| `13900000002` | 吃货小张 | 粉丝 |
| `13900000003` | 美食猎人 | 双向关注 |
| `13900000004` | 杭州土著 | 粉丝 |
| `13900000005` | 探店达人_莉莉 | 有笔记、有粉丝 |
| `13900000006` | 火锅爱好者 | 火锅达人 |
| `13900000010` | 巴适姐 | 成都本地达人 |

开发模式验证码：`123456`

---

## 核心功能清单

### 用户系统
- [x] 手机验证码登录 / 密码登录
- [x] 短信限流（Redis ZSET 滑动窗口：1 级/2 级限制）
- [x] 用户编辑资料（头像/昵称/介绍/性别/城市/生日）
- [x] 每日签到（Redis BitMap）
- [x] 模拟支付
- [x] 我的订单（查看、支付、状态流转）

### 店铺与笔记
- [x] 首页热门笔记瀑布流（支持分页滚动）
- [x] 按分类浏览店铺（5 公里范围内 GEO 搜索）
- [x] 店铺详情 + 优惠券列表
- [x] 发布探店笔记（文字 + 多图）
- [x] 网友评价（关联用户信息）
- [x] 搜索商户（按名称关键字）

### 社交
- [x] 关注/取关博主
- [x] 粉丝列表 / 关注列表
- [x] 共同关注
- [x] 点赞博客
- [x] Feed 流推送（关注博主发布笔记后主动推送）

### 秒杀系统
- [x] 秒杀专区列表
- [x] Redis + Lua 原子校验库存+一人一单
- [x] RabbitMQ 异步下单（解耦削峰）
- [x] 库存预热到 Redis（启动时自动）
- [x] 超时订单自动关闭（Spring Task 定时扫描）
- [x] 乐观锁防止并发覆盖（order.status CAS）
- [x] 幂等性校验（Redis Set 去重）
- [x] AOP 滑动窗口限流（`@RateLimit` 注解）

### 缓存
- [x] 多级缓存：Caffeine L1 → Redis L2 → DB
- [x] 缓存穿透防护（空值缓存 + 短暂 TTL）
- [x] 缓存击穿防护（逻辑过期 + 互斥锁重建）
- [x] 数据一致性：更新 DB → 删除缓存 → MQ 补偿
- [x] 店铺 GEO 数据缓存（Redis Geo）

### 智能客服
- [x] LangChain4j + DeepSeek V4 Pro
- [x] Redis 会话记忆（30 分钟 TTL）
- [x] Function Calling：查询商家 / 到店预约
- [x] SSE 流式响应

---

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.12 | 容器 + MVC 框架 |
| Java | 17 | 语言 |
| MyBatis Plus | 3.5.5 | ORM 框架 |
| Redis | 6.2+ | 分布式缓存 / GEO / 限流 / 会话 |
| Redisson | 3.27 | 分布式锁 |
| RabbitMQ | 3.9 | 消息队列（秒杀异步下单） |
| MySQL | 8.0 | 关系型数据库 |
| Caffeine | 3.x | 本地缓存（L1 缓存） |
| LangChain4j | 0.36.2 | AI 智能客服 |
| DeepSeek | V4 Pro | LLM 模型 |
| Hutool | 5.x | 工具库 |
| Lombok | 1.18+ | 简化 POJO |
| Alibaba SMS | — | 短信验证码 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue.js | 2.x | MVVM 框架 |
| Element UI | 2.x | UI 组件库 |
| Axios | — | HTTP 请求 |
| Nginx | 1.18 | 静态资源服务 + API 反向代理 |

---

## 系统架构

```
┌─────────────────────────────────────────────────────┐
│                    Browser (H5)                      │
│               Vue2 + Element UI + Axios               │
└──────────────────────┬──────────────────────────────┘
                       │ HTTP (localhost:8080)
┌──────────────────────▼──────────────────────────────┐
│                    Nginx (反向代理)                     │
│              / → html/hmdp (静态资源)                   │
│              /api/* → localhost:8081 (后端)            │
└──────────────────────┬──────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────┐
│              Spring Boot 3.2 (端口 8081)               │
├──────────┬──────────┬──────────┬─────────────────────┤
│ Controller│ Service  │  Mapper   │  MQ Receiver       │
├──────────┼──────────┼──────────┼─────────────────────┤
│ Redis    │ MySQL    │RabbitMQ  │  DeepSeek (AI)      │
└──────────┴──────────┴──────────┴─────────────────────┘
```

### 分层说明

```
@RestController  ← HTTP 层，接收请求、返回 Result
      ↓ DTO/Req
@Service         ← 业务逻辑层，@Transactional 事务管理
      ↓ Entity
@Mapper          ← MyBatis Plus 数据访问层
      ↓ SQL
Database         ← MySQL
```

### 核心架构模式

**双拦截器模式**：
- `RefreshTokenInterceptor`（order 0）：所有请求通过，刷新 Redis Token TTL
- `LoginInterceptor`（order 1）：只拦截需登录的路径，校验 UserHolder

**秒杀漏斗**：
```
AOP限流 → Lua原子校验(Redis) → MQ异步落库(RabbitMQ) → DB持久化
```

**多级缓存读取**：
```
Caffeine(L1) → Redis(L2) → DB → 回写 L1+L2
```

---

## 数据库设计

### 核心表

| 表名 | 说明 |
|------|------|
| `tb_user` | 用户表（手机号/昵称/头像） |
| `tb_user_info` | 用户详情（城市/介绍/性别/生日/积分/粉丝数） |
| `tb_shop` | 店铺表（名称/类型/坐标/评分/销量/营业时间） |
| `tb_shop_type` | 店铺分类（美食/KTV/SPA 等 10 类） |
| `tb_voucher` | 优惠券表（普通券 + 秒杀券） |
| `tb_seckill_voucher` | 秒杀券详情（库存/开始时间/结束时间） |
| `tb_voucher_order` | 订单表（用户ID/优惠券ID/状态/支付时间） |
| `tb_blog` | 探店笔记表（图片/内容/点赞数/评论数） |
| `tb_blog_comments` | 评论表 |
| `tb_follow` | 关注关系表 |
| `tb_sign` | 签到记录（兼容旧版，当前用 Redis BitMap） |

---

## 功能模块详解

### 1. 登录模块

- 支持手机验证码登录（阿里云 SMS 或 QQ 邮箱 STMP 开发模式）
- 支持密码登录（MD5 加密验证）
- 登录成功后生成 Token 存入 Redis Hash（`login:token:{token}`）
- Token TTL 30 分钟，每次请求自动续期
- 短信限流：1 分钟最多 1 条，5 分钟触发 1 级/2 级限制

### 2. 秒杀模块

```
1. 用户点击"抢购"
2. AOP @RateLimit 滑动窗口限流（10 qps/用户）
3. Redis Lua 脚本原子校验：
   - 检查库存是否 > 0
   - 检查用户是否已购买（一人一单）
   - 扣减 Redis 库存 + 记录用户到 Set
4. 校验通过 → 生成 VoucherOrder → 发送到 RabbitMQ
5. MQReceiver 消费消息：
   - 幂等去重（Redis Set）
   - 二次校验"一人一单"（DB 级别）
   - CAS 扣减 DB 库存（stock > 0）
   - 保存订单
6. 超时关单：OrderCloseTask 每 20s 扫描未支付 > 15 分钟的订单
   - 乐观锁 eq("status", 1) 避免并发覆盖
   - 回滚 Redis 库存 + 恢复下单资格
```

### 3. 缓存策略

| 策略 | 说明 |
|------|------|
| 穿透防护 | 缓存 null 值（TTL 2 分钟） |
| 击穿防护 | 热点数据逻辑过期 + 互斥锁 |
| 雪崩防护 | Redis TTL 随机偏移 |
| 双写一致性 | 先更 DB → 删缓存 → MQ 补偿 |
| 多级缓存 | Caffeine（10 min）→ Redis（30 min）→ DB |

### 4. GEO 搜索

- Redis GEO 数据结构 key `shop:geo:{typeId}`
- `GEOSEARCH` 以用户坐标为中心、5 公里半径搜索
- 支持距离排序、分页

---

## 待优化与已知问题

### 功能待完善

| 问题 | 优先级 | 说明 |
|------|--------|------|
| 笔记图片展示 | P1 | 部分笔记图片 URL 来自美团/CDN，可能失效；首页瀑布流图片高度不一致 |
| "网友评价" 标签 | P1 | shop-detail 硬编码标签（"味道赞(19)"等）未改为真实数据 |
| 评价打分 | P2 | blog-comments 表无评分字段，前端 el-rate 无数据 |
| 私信/消息中心 | P2 | 暂无私信功能 |
| 店铺搜索优化 | P2 | 名称搜索未加拼音/模糊匹配 |
| 用户 VIP 等级 | P2 | tb_user_info.level 未使用，所有用户显示为 0 |
| 签到日历 | P2 | 只有签到统计接口，前端无签到日历 UI |

### 性能与架构

| 问题 | 优先级 | 说明 |
|------|--------|------|
| N+1 查询 | P2 | BlogServiceImpl.queryHotBlog 逐条查 User 信息 |
| 关单频繁扫描 | P2 | 每 20 秒全表扫描未支付订单，大表可能性能问题 |
| 无 API 版本号 | P3 | 接口路径无 v1/v2 版本前缀 |
| 重复依赖 | P3 | pom.xml 中部分依赖版本冗余 |
| 异常熔断 | P3 | 无 Sentinel/Resilience4j 熔断降级 |

### 前端

| 问题 | 优先级 | 说明 |
|------|--------|------|
| 图片懒加载 | P2 | 首页瀑布流大量图片同时加载 |
| 移动端适配 | P2 | 部分页面在低分辨率设备上布局偏移 |
| 页面过渡动画 | P3 | 页面切换缺少 loading/过渡动画 |

### 安全

| 问题 | 优先级 | 说明 |
|------|--------|------|
| XSS 防护 | P2 | 笔记内容直接 v-html 渲染未过滤 |
| 密码明文传输 | P2 | 登录密码未在前端加密 |
| SQL 注入 | P3 | 低风险（MyBatis Plus 参数化查询） |

### 测试

| 问题 | 优先级 | 说明 |
|------|--------|------|
| 单元测试 | P1 | 项目缺乏测试用例，只有两个测试类且未生效 |
| 集成测试 | P2 | 无 Testcontainers 集成测试 |
| JMeter 压测 | P2 | 秒杀场景未做性能测试 |

---

---

## 项目部署

### 生产环境建议

1. **Redis 主从 + Sentinel**：保证高可用
2. **RabbitMQ 镜像队列**：消息可靠性
3. **MySQL 读写分离**：一主多从 + Proxy
4. **Nginx 负载均衡**：后端多节点部署
5. **日志收集**：ELK / Loki + Grafana
6. **监控告警**：Prometheus + Grafana / Spring Actuator

### 环境变量配置 (.env)

```env
ALIYUN_ACCESS_KEY_ID=your_key
ALIYUN_ACCESS_KEY_SECRET=your_secret
DEEPSEEK_API_KEY=sk-deepseek_key
MAIL_USERNAME=your@qq.com
MAIL_PASSWORD=your_smtp_code
```

---

## 许可证

本项目仅用于个人学习和技术展示。
