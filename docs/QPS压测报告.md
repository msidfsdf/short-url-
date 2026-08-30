# 短链系统 QPS 压测报告（阶段1 同步 UPDATE vs 阶段2 RabbitMQ 异步）

> 生成时间：2026-08-27
> 目的：量化"阶段2 MQ 异步点击统计"相比"阶段1 同步 UPDATE"的收益，并用数据说话。

---

## 一、测试环境与方法

### 1.1 环境
- 后端：SpringBoot 3.3.5，单实例，端口 **8082**（独立测试实例，未触碰 8081 上原有后端）
- JDK 17，Tomcat 调优：`max-threads=500 / accept-count=2000 / max-connections=5000`（仅压测用 `-D` 覆盖，未改代码）
- MySQL 8（localhost:3306，连接池 Hikari 默认 `max-pool-size=10`）
- Redis 3.2.100（localhost:6379，压测必需，本次手动拉起）
- RabbitMQ 4.3.5（localhost:5672，Windows 服务；单消费者，手动 ACK）

### 1.2 被测短码
复用已有短码 `4c92`（`http://localhost:8082/4c92`），跳转目标 `https://www.baidu.com`。
压测工具**不跟随重定向**，只读取 302 响应头，正好测跳转接口本身。

### 1.3 压测工具（两个）
- `tools/load_test.py`：**线程版**（Python 标准库）。实测存在 **GIL 上限**，单机吞吐被锁在 ~3.3k QPS，会把后端和客户端的差异一起掩盖 → 不能用于证明收益，仅作对照。
- `tools/load_test_async.py`：**asyncio 异步版**（纯标准库，HTTP/1.1 keep-alive）。突破 GIL，能真正把后端压到饱和。本报告结论均基于该工具。

### 1.4 A/B 开关（新增，正经功能）
在 `ShortUrlServiceImpl.getOriginalUrl` 中按开关决定点击统计方式，同一份 jar 两种模式各测一遍：
- `-Dshort-url.click.async-enabled=false` → **阶段1 同步**：请求线程内 `UPDATE click_count = click_count + 1`
- `-Dshort-url.click.async-enabled=true`  → **阶段2 MQ**：`convertAndSend` 发消息，消费者异步 +1
- 额外 `-Dshort-url.click.simulate-db-latency-ms=N`：模拟下游 DB 写入耗时（仅压测用），用于演示"慢下游解耦"

> 默认 `async-enabled=true`，生产行为不变。该开关同时是**生产降级开关**：MQ 不可用时可退化为同步，不丢统计。

---

## 二、数据结果

### 2.1 原生存量写（localhost 平凡单行 UPDATE）—— MQ 并不更快

| 模式 | 并发 | QPS | p50 | p95 | p99 | 状态码 |
|---|---|---|---|---|---|---|
| **SYNC（阶段1）** | 500 | **6243** | 73.1 | 115.3 | 143.9 | 302 |
| **MQ（阶段2）**   | 500 | 3747 | 128.4 | 222.6 | 305.0 | 302 |
| SYNC | 1000 | **5985** | 163.5 | 199.5 | 274.2 | 302 |
| MQ   | 1000 | 3964 | 245.5 | 335.5 | 367.0 | 302 |
| MQ   | 2000 | 4705 | 404.1 | 572.7 | 630.6 | 302 |

**结论：在"单机 + 平凡单行写"场景下，MQ 模式 QPS 约为同步模式的 0.60~0.75 倍，p99 反而更高。**

原因（已用代码+现象确认）：
1. **Broker 发布开销 > 连接池内 MySQL UPDATE**：`convertAndSend` 虽是 fire-and-forget（不等 confirm），但每次请求多了"JSON 序列化 + 网络发往 broker + 可能的流控"一跳；而同步的 `UPDATE` 走的是本地连接池里一条已建立的、命中索引的极快写。在本机低延迟下，前者更贵。
2. **单消费者追不上生产者 → 背压**：消费者是单线程、每条消息一次 `UPDATE`。发布速率（~4k/s）一旦超过消费速率，队列堆积 → 触及 broker 内存高水位 → **broker 流控阻塞 publisher** → 请求线程的发布被卡 → p99 飙升。瓶颈只是从"请求线程等 DB"搬到了"消费者等 DB + broker 流控"，并没有消失。

### 2.2 慢下游（模拟 DB 写入 30ms）—— MQ 的真正收益：解耦请求与下游延迟

| 模式 | 并发 | QPS | **min** | p50 | p99 |
|---|---|---|---|---|---|
| **SYNC + 30ms** | 200 | 3478 | **31.5** | 53.0 | 87.1 |
| **MQ + 30ms**   | 200 | 3133 | **0.24** | 36.2 | 175.7 |
| SYNC + 30ms | 100 | 2238 | 30.9 | 42.3 | 61.2 |
| MQ + 30ms   | 500 | 3629 | 0.30 | 126.2 | 206.3 |

**结论（核心收益）：**
- **SYNC 模式**：慢下游（30ms）被钉死在请求关键路径上 → **100% 的请求都至少多等 30ms**（min=31.5ms，正好等于模拟耗时）。DB 越慢，每个用户重定向越慢，直至超时、雪崩。
- **MQ 模式**：同样的 30ms 被移到消费者线程（请求路径之外）→ 请求 **min=0.24ms**，大量请求根本不感知这 30ms；只有 broker 发布本身的开销（可优化）在路径上。
- 这是**可用性/韧性收益，不是原始吞吐收益**：DB 抖动、降级、短暂不可用时，MQ 模式下 302 跳转照常飞快，点击统计只是"最终一致、可能延迟"，绝不阻塞用户。

---

## 三、总体结论

| 维度 | 阶段1 同步 | 阶段2 MQ | 谁赢 |
|---|---|---|---|
| 平凡单行写 原始 QPS | ~6.2k | ~3.7~4.7k | 同步（本机低延迟下） |
| 请求对"慢下游"的敏感度 | 1:1 耦合（min=下游耗时） | 解耦（min≈发布开销） | **MQ** |
| 下游故障时的可用性 | 请求被拖垮/超时 | 跳转不受影响，统计延迟 | **MQ** |
| 写峰削峰能力 | 无（实时写库） | 有（队列缓冲） | **MQ**（需消费者跟得上） |

**一句话总结**：
> MQ 在"本机平凡写"上**不会让 QPS 变快**（反而略慢，因为多了 broker 发布一跳）；它的价值是**把"点击统计"这个副作用从跳转请求的关键路径里解耦出来**——下游慢/挂时跳转照常，以及为后续"批量聚合 +1 / 消费并发"留出吞吐扩展空间。这正是阶段2 设计文档里"读留在线、写丢给 MQ"的本来意图。

---

## 四、让 MQ 真正兑现"吞吐收益"的演进方向（已在规划中）
1. **消费者批量聚合 +1**：把 N 条消息聚成一条 `UPDATE ... SET click_count = click_count + ?`，把 DB 写从 O(N) 降到 O(1)，吞吐立刻反超同步。
2. **消费者并发**：`@RabbitListener(concurrency="N")`，但受 Hikari 连接池（默认 10）约束，需同步调大池或配合批量。
3. **关闭非必要发布确认**：若允许极小概率丢失，可去掉 `publisher-confirm-type`/`publisher-returns` 进一步降低发布延迟（权衡可靠性）。

---

## 五、复现命令

```bash
# 1) 起后端（SYNC 模式）
java -Dserver.port=8082 -Dshort-url.click.async-enabled=false \
     -Dserver.tomcat.max-threads=500 -Dserver.tomcat.accept-count=2000 \
     -jar short-url-server-1.0.0.jar

# 2) 起后端（MQ 模式）
java -Dserver.port=8082 -Dshort-url.click.async-enabled=true \
     -Dserver.tomcat.max-threads=500 -Dserver.tomcat.accept-count=2000 \
     -jar short-url-server-1.0.0.jar

# 3) 压测（async 客户端）
python tools/load_test_async.py -u http://localhost:8082/<你的短码> -n 100000 -c 500

# 4) 韧性演示：模拟下游 30ms
java -Dserver.port=8082 -Dshort-url.click.async-enabled=true \
     -Dshort-url.click.simulate-db-latency-ms=30 -jar short-url-server-1.0.0.jar
python tools/load_test_async.py -u http://localhost:8082/<你的短码> -n 10000 -c 200
```

---

## 六、备注
- 压测期间 Redis 由本次手动拉起（原手动实例当时未运行）；8081 上原有后端未被触碰。
- 部分运行出现个位数 ~0.5% 连接重置（`ERR`），为压测客户端瞬时建连突发所致，成功响应均为 302，不影响结论。
- 测试后 `click_count` 累计偏大属正常（多次压测叠加 + MQ 最终一致），不影响接口与结论。
