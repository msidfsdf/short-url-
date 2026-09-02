# 短链管理系统（Short URL）

一个从 0 到 1 演进的短链服务，按 **功能 → 性能 → 可用性 → 安全** 主线分五个阶段交付。每阶段都有真实压测数据与踩坑记录，不做"为面试加分硬堆技术栈"的事。

> 状态：五阶段全部交付并打 tag（`v1.0` ~ `v5.0`），18/18 单元测试全绿，项目已封版。

---

## 技术栈总览

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3.5 / MyBatis-Plus / Spring AMQP / Redisson 3.30.0 |
| 中间件 | MySQL 8 / Redis / RabbitMQ |
| 前端 | Vue 3 / Vite / Element Plus / axios |
| 工具 | 自研 Feistel 双射置换 / Redis SETNX+Lua 分布式锁 / Redisson RBloomFilter |
| 压测 | Python urllib + 线程池，MySQL `Com_select`/`Com_update` 全局计数器交叉验证 |

---

## 五阶段演进路线

### 阶段1 · MVP（tag `v1.0`）

**新增技术栈**：Spring Boot 3.3.5 / MySQL / Redis / MyBatis-Plus / Vue 3 + Vite + Element Plus

**业务流程**

```
创建短链：长链入库 → 拿自增 id → Base62(id) 生成短码 → 回写 DB + 写 Redis（TTL 7 天）
短链跳转：GET /{code} → Redis 查 → 未命中查 DB → 回写缓存 → HTTP 302 重定向
点击统计：跳转时同步 UPDATE click_count = click_count + 1
```

**验收**：三接口（创建 / 列表 / 跳转）跑通；**埋下两个坑**——跳转同步写库 + 短码连号可枚举。

---

### 阶段2 · RabbitMQ 异步点击统计（tag `v2.0`）

**新增技术栈**：RabbitMQ / spring-boot-starter-amqp / DirectExchange + 死信队列 / 手动 ACK + Jackson2JsonMessageConverter

**业务流程**

```
跳转拿到原 URL 后兵分两路：
  ├─ 同步：立即返回 HTTP 302（用户感知 ~100ms，不等统计）
  └─ 异步：发 ClickMessage{shortCode, ts} 到 MQ → 消费者手动 ACK
                                   │
                ┌──────────────────┴──────────────────┐
           basicAck 成功                    basicNack(requeue=false)
                │                                  │
                ▼                                  ▼
       UPDATE click_count+1               进死信队列待人工排查
```

**核心论点**：读路径必须同步（要当场返回 URL），**只有"点击 +1"这个写副作用能异步化**。MQ 解决的是"解耦 + 削峰 + 失败兜底"，不是 QPS。

**验收 + 修 Bug**
- 跳转接口不再被写库阻塞
- **修 Bug 1**：缓存 TTL 固定 7 天 → 过期短链在缓存期内仍能跳转。改为 `min(7 天, 到 expireTime 剩余时间)`
- **修 Bug 2**：`original_url` 普通索引 → 并发创建同一长链产生重复短码。升级为前缀唯一索引 + 代码捕获 `DuplicateKeyException`

---

### 阶段3 · MQ 消费端批量聚合（tag `v3.0`）

**新增技术栈**：Spring AMQP 批量消费（`consumerBatchEnabled` / `batchSize=100` / `receiveTimeout=250ms`）/ `List<Message>` 监听器签名 / HashMap merge 聚合 / `UPDATE click_count = click_count + N` / 批量 ACK（`basicAck(lastTag, multiple=true)`）

**业务流程**

```
① 用户点击 → Redis 命中 → 立即 302
② 发布侧 fire-and-forget 发 ClickMessage
③ 监听容器攒批：满 100 条 或 等满 200ms 整批交付
④ 消费者 HashMap 按 shortCode 合并：[a,a,b,a] → {a:3, b:1}
⑤ recordClickBatch：M 次 UPDATE（M = 批内唯一短码数，远小于 N）
⑥ 成功 basicAck(lastTag, multiple=true) 一次签收整批
   失败逐条 basicNack(requeue=false) → 全部进死信，保持 at-least-once
```

**核心论点**：优化的不是"跑得更快"，是"每趟搬得更多"——慢下游每趟 30ms 绕不过去，批量让每趟从搬 1 块变成搬 100 块。

**验收数据（10 万次点击 / 500 并发）**

| 指标 | 阶段2 逐条 | 阶段3 批量 | 变化 |
|---|---:|---:|---:|
| DB UPDATE 次数 | 99,500 | **995** | **-99%** |
| 全量落库耗时（30ms 慢下游，1 万次） | 376.8s | **8.9s** | **-97.6%** |
| 可持续消费速率 | ~33 条/秒 | **~3300 条/秒** | 100× |
| 落库完整率 | 100% | 100% | 0 丢失 0 重复 |

**踩坑**：Spring AMQP 3.1.7 批量模式下 `@Header(DELIVERY_TAG)` 注入失效 → 整批拒收零落库。改用 `List<Message>` + `message.getMessageProperties().getDeliveryTag()` 手动取，绕开 header 解析。

> 原预判"批量聚合能提升 QPS"被实测证伪（消息太小、broker 流控从未触发，瓶颈在发布侧），如实修正定位为"降 DB 写压力 + 收敛速度"。

---

### 阶段4 · 缓存穿透 / 击穿治理（tag `v4.0`）

**新增技术栈**：Redisson `RBloomFilter`（Redis Bitmap 共享持久，n=100万 / p=1%，墙约 1.2MB / k=7）/ `RedisLock`（SETNX + Lua 原子释放防误删）/ `CacheValue` 逻辑过期包装（JSON：`u`/`e`/`n`）/ `CacheGuardMetrics` 11 个 AtomicLong 计数器 / `short-url.cache-guard.mode` 三态开关（`none`/`mutex`/`logical-expiry`）

**业务流程（四层防护链路）**

```
GET /{shortCode}
   ↓  [mode=none 走阶段1~3 原逻辑作 A/B 基线]
① 短码格式校验     长度 1~10 且只含 62 进制字符集，非法直接 404（挡穿透）
   ↓
② 布隆过滤器       判"一定不存在"直接 404，零 DB 查询（挡穿透）
   ↓
③ 缓存策略         mutex：抢锁+双重检查+没抢到的等待重试（挡击穿）
                   logical-expiry：旧值先返回+抢锁提交异步重建（挡击穿）
   ↓
④ 空值缓存         布隆误判漏过来的非法短码，查完 DB 写空值标记 TTL 120s（挡穿透兜底）
   ↓
                  DB
```

**核心论点**：四层谁也替不了谁——① 挡"输入根本不像短码"、② 挡"格式合法但没登记过"、③ 挡"真存在但缓存没了"、④ 兜"布隆说可能存在但 DB 真没有"。`mutex` 与 `logical-expiry` 都用锁、都把 DB 查询从几千次降到 1 次，**区别只是"其他线程要不要等"**。

**验收数据（穿透组 5000 随机假短码 / 并发 300；击穿组 清缓存后 500 并发打同一热 key）**

| 场景 | 指标 | none（基线） | mutex | logical-expiry |
|---|---|---:|---:|---:|
| 穿透组 | 打到 DB 次数 | 5000 | **0** | **0** |
| 击穿组 | 打到 DB 次数 | 14 | **1** | **1** |
| 击穿组 | QPS | 1504.6 | 1920.9 | **1924.6** |
| 失效瞬间 | p99（ms） | 202.56 | 144.09 | **36.26** |

**踩坑**：logical-expiry 第一版冷启动写的是裸 URL → `CacheValue.parse` 恒为 null → 逻辑过期机制形同虚设。功能正常、延迟正常，**是 11 个 AtomicLong 计数器把它揪出来的**——可观测性本身就是工程能力。

---

### 阶段5 · 短码防枚举 + 生产配置修正（tag `v5.0`）

**新增技术栈**：Feistel 网络（32 位块 / 10 轮 / 10 个固定子密钥）/ 黄金值回归测试 / MyBatis `NoLoggingImpl`

**业务流程（生成路径唯一改动，跳转链路零改动）**

```
长链入库拿自增 id（BIGINT, AUTO_INCREMENT 从 1000000 起）
   ↓
FeistelUtils.encrypt(id)        ← 新增：双射置换，id=1000000 → 4195963162
   ↓                              （连号 → 无规律，[0, 2^32-1] 一一对应）
Base62Utils.encode(shuffled)    ← 不变：纯进制转换
   ↓
short_code 存 DB + 加布隆 + 写 Redis（后续链路全部不变）
```

跳转按 `WHERE short_code = ?` 字符串查库，不依赖 id 反解 → 新老短码同表共存全兼容。

**核心论点**

- **Feistel = 置换 + 可逆 → 天然附带零碰撞红利**；**哈希 = 压缩 + 单向 → 天然附带碰撞 trade-off**。
- 零碰撞不是"拿空间换的"——输入输出都是 32 位没多占 1 bit，是"定长+可逆"结构自带。真正用空间换容量的是**短码位数**本身。
- 零碰撞证明：归谬——若 A、B 洗完都是 C，对 C 逆洗结果必须唯一 → 矛盾 → 不可能撞。

**验收数据**

| 项 | 结果 |
|---|---|
| 100 万连续 id 可逆率 | **100%**（纯 CPU 约 0.3s） |
| 100 万短码 HashSet 双重判重 | **0 次碰撞** |
| 连号消失 | `4c9g/4c9h/4c9i` → `4zXPHc/iBl5D/1S9K9k`（首字符都不同） |
| 单元测试 | CacheGuard 5 + Feistel 7 + RedisBloom 6 = **18/18 全绿** |
| 兼容性 | id=1000020 短码 `4c9m`（旧）与 id=1000021 短码 `2jRmiR`（新）同表共存 |

**踩坑（必讲）**：初版 `encrypt` 的 `result==0 兜底改 1` 破坏双射——全空间恰好有一个 id（=decrypt(0)，>100 万）映射到 0，兜底改 1 后与 `encrypt(decrypt(1))` 撞车。100 万单测没抓到是因为那个 id 不在扫描范围。**结构性保证不能靠抽样测试验证，要靠数学推理 + 边界用例锁死**。修复方式：0 原样返回 + 黄金值回归锚点（`encrypt(1_000_000)=4195963162 → 4zXPHc`），任何人改 KEYS/轮数立刻红。

> 同阶段修正生产配置隐患：`application.yml` 的 `log-impl` 从 `StdOutImpl` 改为 `NoLoggingImpl`，避免每条 SQL 同步打 stdout 在高并发下拖垮 JVM。

---

## 项目结构

```
short-url/
├── short-url-server/                # Spring Boot 后端
│   └── src/main/java/com/shorturl/
│       ├── controller/              # 短链接口 + 计数器接口
│       ├── service/impl/            # ShortUrlServiceImpl（创建/跳转/缓存治理核心）
│       ├── mq/                      # ClickProducer / ClickCountConsumer / ClickMessage
│       ├── utils/                   # Base62Utils / FeistelUtils / RedisBloomFilter / RedisLock
│       ├── common/                  # CacheValue / CacheGuardMetrics / R / GlobalExceptionHandler
│       └── config/                  # RabbitMQConfig / CacheGuardConfig / RedissonConfig
├── short-url-web/                   # Vue 3 前端
├── sql/                             # 建表 + 唯一索引迁移脚本
├── tools/                           # Python 压测脚本（load_test / batch / cache_guard）
├── docs/                            # 五份阶段版本说明书 + 压测报告 + 会话上下文
└── PROJECT_HISTORY.md               # 完整历史档案（约 1200 行）
```

---

## 快速启动

```bash
# 后端打包
$env:JAVA_HOME = "C:\Users\LENOVO\Desktop\jdk-17.0.18+8"
& "C:\Users\LENOVO\.tools\maven\apache-maven-3.9.9\bin\mvn.cmd" package -DskipTests -B -q

# 启动（默认 logical-expiry 模式）
& "$env:JAVA_HOME\bin\java.exe" `
    "-Dfile.encoding=UTF-8" `
    "-Dserver.port=8082" `
    "-Dshort-url.cache-guard.mode=logical-expiry" `
    "-Dmybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl" `
    -jar short-url-server/target/short-url-server-1.0.0.jar

# 冒烟
curl.exe -s -i http://localhost:8082/4c9g                                   # 期望 302
curl.exe -s -i http://localhost:8082/zzzz99                                 # 期望 404（布隆拦截）

# 单元测试
& "C:\Users\LENOVO\.tools\maven\apache-maven-3.9.9\bin\mvn.cmd" test "-Dfile.encoding=UTF-8"
```

**前置依赖**：MySQL 8 / Redis / RabbitMQ（5672 端口可用即可，管理台非必需）

---

## 单元测试

| 测试类 | 用例数 | 覆盖 |
|---|---:|---|
| `RedisBloomFilterTest` | 6 | 误判率实测 / 不漏判 / 参数推导验证 |
| `CacheGuardTest` | 5 | 穿透 / 击穿 / 空值缓存 / 格式校验 |
| `FeistelUtilsTest` | 7 | 可逆性（100 万 id 全扫）/ 零碰撞 / 非连号 / 双射边界 / 黄金值回归 |
| **合计** | **18** | **全部通过** |

---

## 工程约定

- **绝不擅自 git commit/push**：所有改动只留工作区，由用户点名才推
- **保留任意阶段回滚能力**：tag `v1.0`~`v5.0` 对应五阶段
- **压测 3 闸**：① 死信队列深度=0；② `click_count` 增量/成功请求数≥99%；③ 后端 stdout<20MB
- **A/B 对照必须两个独立 jar**：单条/批量监听器签名不兼容，代码里开关切是死代码
- **企业实用性原则**：任何改进先回答"企业真会遇到吗？ROI 为正吗？"
- **已明确否决**：❌ 微服务拆分（3 接口单机单人过度设计） ❌ SpringAI 智能标题 ❌ 多级缓存 Caffeine

---

## 详细文档

- [PROJECT_HISTORY.md](PROJECT_HISTORY.md) — 完整历史档案（技术选型 / 踩坑 / 环境路径 / 本机陷阱）
- [docs/阶段1版本说明书.md](docs/阶段1版本说明书.md) ~ [docs/阶段5版本说明书.md](docs/阶段5版本说明书.md) — 每阶段独立说明书
- [docs/QPS压测报告.md](docs/QPS压测报告.md) — 阶段3/4 压测完整数据
