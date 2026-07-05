# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Spring Boot application for a Dianping-like restaurant review and voucher seckill platform (惠享生活). The project implements features including phone-number authentication via SMS, shop browsing with GEO-based nearby search, blog/review system, seckill voucher ordering, AI customer service, and user sign-in tracking.

**Tech Stack:** Spring Boot 3.2.12, Java 17, MyBatis Plus 3.5.5, Redis 6.2+, RabbitMQ 3.9, MySQL, Redisson 3.27, Caffeine, Hutool, LangChain4j 0.36 (DeepSeek V4 Pro), Guava (only legacy)

**Environment Requirements:**
- Java 17
- Redis 6.2+ (required for GEOSEARCH command)
- RabbitMQ 3.9
- MySQL database (utf8mb4)

## Build and Run Commands

### Backend
```bash
# Build the project
mvn clean package -DskipTests

# Run the application (default port: 8081)
mvn spring-boot:run

# Or run the jar directly
java -jar target/huixiang-life-0.0.1-SNAPSHOT.jar
```

### Frontend
```bash
# Start nginx to serve frontend (located in nginx-1.18.0 directory)
nginx-1.18.0/nginx.exe
```

### Database Setup
- Execute `src/main/resources/db/hmdp.sql` to initialize database schema and seed data
- Execute `src/main/resources/db/migration.sql` for upgrade fields (version, cancel_time, reservation table)
- Configure MySQL connection in `src/main/resources/application.yaml`

### Environment Variables
All sensitive configs are read from `.env` file (in `.gitignore`):
```env
ALIYUN_ACCESS_KEY_ID=your_access_key
ALIYUN_ACCESS_KEY_SECRET=your_access_secret
DEEPSEEK_API_KEY=sk-deepseek_key
```

### Load .env and start
```powershell
Get-Content .env | ForEach-Object {
    if ($_ -match '^([^#].+?)=(.+)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
mvn spring-boot:run
```

## Architecture

### Layered Structure
Standard Spring Boot three-tier architecture:
- **Controller**: REST API endpoints (`com.hmdp.controller.*`)
- **Service**: Business logic (`com.hmdp.service.impl.*`)
- **Mapper**: MyBatis Plus data access (`com.hmdp.mapper.*`)

### Key Architectural Patterns

#### 1. Redis-Based Session Management
- No HTTP session usage; all user sessions stored in Redis
- Token stored in Redis hash with structure: `login:token:{token}` → hash containing userId, nickName, icon, agreedPolicy
- Session TTL: 30 minutes (configurable in `RedisConstants.LOGIN_USER_TTL`)
- Session refresh handled by `RefreshTokenInterceptor` (order 0)

#### 2. Dual Interceptor Pattern
Two interceptors work together for authentication:
- **RefreshTokenInterceptor** (order 0): Runs on ALL requests, refreshes token TTL if user exists
- **LoginInterceptor** (order 1): Runs only on protected paths, enforces authentication requirement
- User context passed via ThreadLocal (`UserHolder`)

**Important**: `LoginInterceptor` checks if user exists in ThreadLocal (set by RefreshTokenInterceptor). The interceptors must maintain this order.

#### 3. User Context Management
- `UserHolder`: ThreadLocal-based storage for current user (`UserDTO`)
- Always call `UserHolder.removeUser()` in interceptor `afterCompletion()` to prevent memory leaks
- Access current user: `UserHolder.getUser()`

#### 4. Global Unique ID Generation
`RedisIdWorker` generates distributed unique IDs using:
- Timestamp component (32 bits): current time - epoch (2022-01-01)
- Sequence component (32 bits): Redis increment counter per day
- Pattern: `{timestamp << 32 | sequence}`
- Key structure: `inc:{prefix}:{yyyy:MM:dd}`

#### 5. Seckill System Architecture
High-consecutive voucher ordering uses:

**Rate Limiting** (multiple layers):
- **AOP RateLimit**: Redis + Lua sliding window (`@RateLimit` annotation, supports GLOBAL/IP/USER)
- **User Time Window** (Redis ZSET): Prevents abuse - tracks verification code sends per phone number
  - 1 minute window: max 1 send
  - 5 minute window: max 5 sends (triggers 1st level limit: 5 min ban)
  - Pattern 8,11,14... sends (triggers 2nd level limit: 20 min ban)

**Lua Script Execution** (`seckill.lua`):
- Atomic operations: stock check + user order check + stock deduction + order creation
- Returns: 0 (success), 1 (insufficient stock), 2 (duplicate order)
- Redis keys: `seckill:stock:{voucherId}` and `seckill:order:{voucherId}` (SET for user orders)
- **Important**: Always check stock key exists via `tonumber(redis.call('get', stockKey)) or 0` to avoid Lua nil error

**Async Processing** (RabbitMQ):
- Lua script validates → if success, create order object → send to MQ
- `MQSender`: Sends order JSON to `seckillExchange` with routing key `seckill.message`
- `MQReceiver`: Consumes from `seckillQueue`, performs final validation and database save
- Dead letter: failed messages go to `seckillDeadQueue` (`default-requeue-rejected: false`)
- Idempotency: `order:processed:{orderId}` Redis Set with 24h TTL prevents duplicate consumption
- Transaction on consumer side ensures data consistency

**Important**: The sec kill flow separates validation (Lua + Redis) from persistence (MQ + DB). Never bypass Lua script when implementing sec kill features.

#### 6. Cache Strategy (Multi-level: Caffeine + Redis)

**Read Path**: L1 Caffeine → L2 Redis → DB → write back L1+L2

**Cache Penetration Prevention** (`CacheClient.queryWithPassThrough`):
- Stores null values in Redis with short TTL (2 min) when data doesn't exist
- Caffeine also caches null-string markers

**Cache Breakdown Prevention**:
- Logical expiration for hotspot data (`queryWithLogicalExpire`)
- Mutex lock for cold-start rebuilds (`queryWithMutex`)

**Data Consistency**:
- "Update DB first, then delete cache" pattern
- Cache eviction failure triggers MQ compensation (`MQSender.sendCacheDeleteMessage`)
- Redis TTL expires as last resort

**Cache Key Naming Convention**:
- Follow `RedisConstants` for all Redis key prefixes
- Pattern: `{prefix}:{identifier}` (e.g., `cache:shop:1`, `login:token:abc123`)

#### 7. GEO Data for Shop Locations
- Shops stored in Redis GEO structure keyed by type: `shop:geo:{typeId}`
- Use `stringRedisTemplate.opsForGeo()` for GEO operations
- Test method `loadShopData()` in `HuiXiangApplicationTests` initializes GEO data from database

#### 8. User Sign-In Tracking
- Uses Redis BitMap: `sign:{userId}:yyyyMM`
- Bit offset = day of month - 1
- BITFIELD command for counting consecutive sign-ins

#### 9. Order Auto-Close (Spring Task)
- `OrderCloseTask` runs every 20 seconds, scans unpaid orders older than 15 min
- Optimistic lock: `update().eq("status", 1)` prevents concurrent pay/close race
- On close: roll back Redis stock + restore user's eligibility

#### 10. AI Customer Service (LangChain4j + DeepSeek)
- Configuration: `application.yaml` → `langchain4j.open-ai.chat-model` (base-url: `https://api.deepseek.com`)
- Redis-powered chat memory (`RedisChatMemoryStore`) with 30 min TTL
- Function Calling: `ShopTools.queryShop()` and `ShopTools.makeReservation()`
- Assistant: `AiServices.builder(CustomerServiceAssistant.class)` via `CustomerServiceConfig`

### Service Layer Patterns

**MyBatis Plus Usage**:
- Extend `ServiceImpl<Mapper, Entity>` for CRUD operations
- Use `query()` chain for queries: `query().eq("field", value).one()`
- Use `update()` chain for updates: `update().setSql("stock = stock - 1").eq("id", value).update()`
- Lambda updates supported but SQL-style updates preferred for complex operations
- **Important**: `query().count()` returns `Long` in MP 3.5 (was Integer in 3.4)

**Transaction Management**:
- Use `@Transactional` on service methods that modify multiple tables
- MQ consumer methods (`MQReceiver`) use `@Transactional` for order persistence

**Optimistic Lock (Version)**:
- `VoucherOrder` entity has `@Version` field (compatible with MyBatis Plus 3.5)
- `OrderCloseTask` and `payOrder` use `.eq("status", 1)` for CAS instead of version auto-increment
- Profile: `MybatisConfig` registers `OptimisticLockerInnerInterceptor`

### Controller Layer

**Result Pattern**:
- All endpoints return `Result` object (success/fail with data)
- Use `Result.ok(data)` for success, `Result.fail("message")` for errors

**Request Handling**:
- Login verification code: `POST /user/code` (SMS via Alibaba Cloud)
- Login: `POST /user/login` (LoginFormDTO with phone + code)
- Password login: `POST /user/login` (LoginFormDTO with phone + password)
- Protected endpoints require token in `authorization` header

## Testing

Test class: `HuiXiangApplicationTests.java`

Key test methods:
- `loadShopData()`: Initialize shop GEO data in Redis (run once after DB setup)
- `testHyperLogLog()`: HyperLogLog demo for UV counting
- `saveShop2Redis()`: Cache shop data with logical expiration

Run tests:
```bash
mvn test
```

## Important Constants

All Redis key prefixes and TTL values defined in `RedisConstants`:
- `LOGIN_USER_KEY`: `login:token:`
- `CACHE_SHOP_KEY`: `cache:shop:`
- `SECKILL_STOCK_KEY`: `seckill:stock:`
- `USER_SIGN_KEY`: `sign:`
- Review constants before adding new Redis keys

## Configuration Classes

- `MvcConfig`: Interceptor registration, excludes public paths from auth
- `RedissonConfig`: removed (uses `redisson-spring-boot-starter` auto-config)
- `RabbitMQTopicConfig`: MQ queue/exchange/binding + dead letter setup
- `MybatisConfig`: MyBatis Plus configuration with `OptimisticLockerInnerInterceptor`
- `CustomerServiceConfig`: LangChain4j AI assistant configuration
- `WebExceptionAdvice`: Global exception handler (returns `e.getMessage()` not generic "server error")

## Key Files Reference

- Entry point: `HuiXiangApplication.java`
- Application config: `src/main/resources/application.yaml`
- Database schema: `src/main/resources/db/hmdp.sql`
- DB migration: `src/main/resources/db/migration.sql`
- Lua script: `src/main/resources/seckill.lua`
- Interceptors: `LoginInterceptor.java`, `RefreshTokenInterceptor.java`
- User holder: `UserHolder.java`
- ID generator: `RedisIdWorker.java`
- Cache: `CacheClient.java` (Caffeine + Redis)
- Rate limit: `aop/RateLimit.java` + `aop/RateLimitAspect.java`
- Order close: `task/OrderCloseTask.java`
- MQ: `mq/MQSender.java`, `mq/MQReceiver.java`
- AI: `ai/CustomerServiceConfig.java`, `ai/CustomerServiceAssistant.java`, `ai/RedisChatMemoryStore.java`, `ai/tools/ShopTools.java`
- Redis constants: `RedisConstants.java`
