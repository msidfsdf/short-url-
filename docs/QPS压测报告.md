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

## 四、让 MQ 真正兑现"吞吐收益"的演进方向（第 1 项已实现，原预期落空，见第六章）

1. **消费者批量聚合 +1**（**已实现并压测，原预期被证伪**）
   - **做法**：把 N 条消息在消费端用 HashMap 按短码合并，聚成一条 `UPDATE ... SET click_count = click_count + N`，DB 写从 O(N) 降到 O(N/100)。
   - **实测收益**：DB 写次数 **-99%**（99,500 → 995）；慢下游场景全量落库耗时 **376.8s → 8.9s（快 42 倍）**。
   - **原预期落空**：当初判断"消费提速 → 消除队列堆积 → 解除 broker 流控 → QPS 反超同步"。实测 **QPS 未提升**：批量 4224.6 < 逐条 4672.8 < 同步 9227.9（同一场三方对照，详见 6.3 与 6.7）。
   - **根因**：本场景单条 ClickMessage 约 60 字节，10 万条合计约 6MB，远未触及 RabbitMQ 默认内存高水位（物理内存 40%，本机数 GB）→ **broker 流控从未触发**，队列堆积也就从未拖累 QPS。真正的 QPS 天花板在**发布侧**（JSON 序列化 + 发往 broker 的一跳往返 + publisher-confirm 回执），批量聚合不解决它。
   - **修正后的定位**：批量聚合解决的是**数据库写压力**与**最终一致性收敛速度**，不是接口吞吐。只有在"消息体较大 / 持续堆积足以触发 broker 流控"的场景下它才可能带动 QPS——本次 30ms 慢下游组观测到 +6.8%，是唯一的正向信号，但幅度有限。

2. **消费者并发**：`@RabbitListener(concurrency="N")`，但受 Hikari 连接池（默认 10）约束，需同步调大池或配合批量。
3. **关闭非必要发布确认**：若允许极小概率丢失，可去掉 `publisher-confirm-type`/`publisher-returns` 进一步降低发布延迟（权衡可靠性）。——**这条才是真正能提升 QPS 的方向**：第 1 项的实测已表明 QPS 瓶颈在发布侧而非消费侧。

> **教训**：性能预测必须标注为"待验证假设"，实现后要用**同一场**的对照实验去证伪或确认，不能把预期当成既成事实写进结论。本章第 1 项当初就是当成既成事实写的，导致留下错误断言。

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

## 六、批量聚合 vs 逐条消费 对照测试（阶段2 原代码 vs 批量聚合）

> 生成时间：2026-08-30
> 目的：验证"MQ 消费端 HashMap 合并 + UPDATE +N"相比"每条消息 1 次 UPDATE"的真实收益。
>
> ⚠️ **本章为重写版本**。此前一版基于"同一份 jar + `batch-enabled` 开关"的 A/B 数据已全部作废，原因见 6.5。

### 6.1 测试方法：两个独立 jar，而不是一个开关

对照组与实验组是**两份不同的代码、打出的两个不同 jar**：

| | 对照组 | 实验组 |
|---|---|---|
| 代码 | 阶段2 原样（commit `2037ac0`） | 阶段2 + 批量聚合改动 |
| 消费者签名 | `ClickMessage` 单条 | `List<Message>` 批量 |
| 落库方式 | 每消息 1 次 `UPDATE +1` | 批内 HashMap 合并后 `UPDATE +N` |
| jar | `.workbuddy/jar-stage2.jar` | `.workbuddy/jar-batch.jar` |

代码版本切换用 `git diff` 导出的补丁正反向应用完成，**不使用 `git stash`**（原因见 6.5）。

### 6.2 正确性闸门

每组压测结束后必须全部满足，否则该组判废、脚本以退出码 2 结束：

| 闸门 | 判定标准 |
|---|---|
| 落库完整性 | `click_count` 增量 ÷ 成功请求数 ≥ 99% |
| 死信队列 | 深度 == 0（不可采集时降级，由落库增量兜底判定） |
| 后端日志 | < 20MB（膨胀说明在刷异常堆栈，I/O 会污染被测系统） |

**四组结果均为落库率 100.00%、VERDICT: PASS。**

### 6.3 数据结果

#### 6.3.1 平凡本地写（simulate-db-latency-ms=0），100k 请求 / 500 并发

| 指标 | 对照组：阶段2 逐条 | 实验组：批量聚合 | 变化 |
|---|---|---|---|
| 吞吐 (QPS) | 4672.8 | 4224.6 | -9.6% |
| 延迟 avg (ms) | 104.58 | 115.88 | +10.8% |
| 延迟 p50 (ms) | 100.51 | 119.34 | +18.7% |
| 延迟 p95 (ms) | 199.74 | 196.60 | -1.6% |
| 延迟 p99 (ms) | 252.38 | 249.93 | -1.0% |
| 延迟 max (ms) | 345.77 | 328.12 | -5.1% |
| **全部点击落库耗时** | **52.2s** | **7.7s** | **-85.2%** ⭐ |
| **DB UPDATE 次数** | **99500** | **995** | **-99.0%** ⭐ |
| 每请求平均写次数 | 1.0000 | 0.0100 | -99.0% |
| 成功请求 | 99500（ERR 500） | 99500（ERR 500） | 一致 |

#### 6.3.2 慢下游（simulate-db-latency-ms=30），10k 请求 / 200 并发

| 指标 | 对照组：阶段2 逐条 | 实验组：批量聚合 | 变化 |
|---|---|---|---|
| 吞吐 (QPS) | 3098.0 | 3309.5 | +6.8% |
| 延迟 avg (ms) | 59.36 | 53.12 | -10.5% |
| 延迟 p50 (ms) | 55.76 | 9.24 | -83.4% ⭐ |
| 延迟 p95 (ms) | 133.12 | 140.91 | +5.8% |
| 延迟 p99 (ms) | 154.10 | 211.42 | +37.2% |
| **全部点击落库耗时** | **376.8s** | **8.9s** | **-97.6%（快 42 倍）** ⭐ |
| **DB UPDATE 次数** | **10000** | **100** | **-99.0%** ⭐ |
| 每请求平均写次数 | 1.0000 | 0.0100 | -99.0% |
| 成功请求 | 10000 | 10000 | 一致 |

### 6.4 结论

**收益稳定且量级显著（两组场景一致）：**

1. **DB 写次数降 99%**——10 万次点击从 99500 次 UPDATE 降到 995 次；慢下游场景从 10000 降到 100。压缩比稳定在 100:1，与 `batch-size=100` 的理论值完全吻合（99500 ÷ 100 ≈ 995）。

2. **最终一致性收敛时间大幅缩短**——0ms 场景 52.2s → 7.7s（-85%）；30ms 慢下游场景 **376.8s → 8.9s（-97.6%，快 42 倍）**。这是慢下游场景下最关键的收益：逐条消费时单消费者串行处理，1 万条 × 30ms 需要 6 分多钟才能追平，批量聚合只需 9 秒。

**HTTP 层指标基本持平，方向不一致，不做因果解读：**

- 0ms 场景批量版 QPS 低 9.6%、p50 高 18.7%；30ms 场景批量版 QPS 高 6.8%、p50 低 83.4%。
- p95/p99 两组互有胜负，差异方向不一致。
- **批量聚合本来就不是用来提升跳转接口 QPS 的**。跳转请求命中 Redis 后直接 302，写库早已异步解耦，消费端怎么优化都不该显著影响它。观测到的差异更接近单次运行噪声。

**一句话总结**：批量聚合把"消费端写库次数"和"最终一致性收敛时间"各压掉一到两个数量级，HTTP 层指标基本不变。这是**用极小的接口侧代价，换下游数据库压力的陡降**，且在慢下游场景下价值最大。

### 6.5 前序数据作废说明（重要）

此前一版第六章的数据（QPS 3329.8 → 3523.8、p99 -25.8% 等）**已全部作废**。事后核查发现五处缺陷，任何一条都足以让结论不成立：

1. **对照组污染（死代码）**：对照组并非阶段2 原代码，而是"改过的代码 + `batch-enabled=false` 开关"。`consumerBatchEnabled=false` 时容器走单条适配器，会把单个 JSON Object 喂给 `List<ClickMessage>` 参数，Jackson 反序列化在进入方法体之前就失败——那个"逐条分支"是**永远执行不到的死代码**。实测对照组 **56374 次反序列化失败、0 次落库**，QPS 数字测的是"消费者全死"的系统。

2. **实验组同样全挂**：spring-amqp 3.1.7 在批量模式下拿不到 `amqp_deliveryTag` header（该版本 `SimpleRabbitListenerContainerFactory` 没有 `setBatchAckMode` 方法），每批抛 `Missing header`，**四组 SQL 执行次数均为 0**。

3. **判定逻辑失效**：压测脚本把"队列深度读取失败(-1)"当成"队列已空(0)"处理，四组都呈现 `QueueDrained=True` 的假象，导致问题当时未被发现。

4. **竞争消费者**：测试期间 IntelliJ 中运行着另一个 short-url 实例，与压测后端抢同一个队列（约占 19% 消息）。它使批量组的 UPDATE 计数从理论值 995 虚高到 19559。

5. **日志 I/O 不公平**：MyBatis 逐条 SQL 日志使对照组日志量远大于实验组（825MB vs 17KB），同步 stdout I/O 不公平地拖慢对照组。

**本章针对上述问题的修复**：

| 缺陷 | 修复 |
|---|---|
| 对照组污染 | 两个独立 jar，对照组用阶段2 原代码，不再有开关分支 |
| deliveryTag 取不到 | 改用 `List<Message>` 参数 + `getMessageProperties().getDeliveryTag()` 手动取 |
| 判定逻辑失效 | 以 MySQL `click_count` 稳态替代队列深度判定 |
| 竞争消费者 | 压测前确认 8082 与队列无其他消费者 |
| 日志 I/O 不公平 | 关闭 MyBatis SQL 日志，改用 MySQL `Com_update` 计数器统计写次数 |

### 6.6 复现命令

```bash
# 0) 前置：确认无竞争消费者（8082 无监听、无其他 short-url 的 java 进程），
#    并让 Redis 可写：
#    redis-cli CONFIG SET stop-writes-on-bgsave-error no
#    redis-cli CONFIG SET dir <可写目录>

# 1) 导出当前改动为补丁（用于来回切换代码版本，比 git stash 安全）
git diff HEAD -- \
  short-url-server/src/main/java/com/shorturl/config/RabbitMQConfig.java \
  short-url-server/src/main/java/com/shorturl/mq/ClickCountConsumer.java \
  short-url-server/src/main/java/com/shorturl/service/IShortUrlService.java \
  short-url-server/src/main/java/com/shorturl/service/impl/ShortUrlServiceImpl.java \
  short-url-server/src/main/resources/application.yml \
  > .workbuddy/stage3-batch-backup.patch

# 2) 切回阶段2，打对照组 jar
patch -p1 -R < .workbuddy/stage3-batch-backup.patch
mvn clean package -DskipTests
cp target/short-url-server-1.0.0.jar .workbuddy/jar-stage2.jar

# 3) 恢复批量聚合代码，打实验组 jar
patch -p1 < .workbuddy/stage3-batch-backup.patch
mvn clean package -DskipTests
cp target/short-url-server-1.0.0.jar .workbuddy/jar-batch.jar

# 4) 跑压测（每次先切换 target 里的 jar）
cp .workbuddy/jar-stage2.jar target/short-url-server-1.0.0.jar
python tools/run_load_test_batch.py --simulate-latency-ms 0 \
    --label A2_stage2 --total 100000 --concurrency 500 --short-code 4c92

cp .workbuddy/jar-batch.jar target/short-url-server-1.0.0.jar
python tools/run_load_test_batch.py --simulate-latency-ms 0 \
    --label B2_batch --total 100000 --concurrency 500 --short-code 4c92

# 慢下游：对照组需放宽等待上限（单消费者串行，1万条 × 30ms ≈ 300s）
cp .workbuddy/jar-stage2.jar target/short-url-server-1.0.0.jar
python tools/run_load_test_batch.py --simulate-latency-ms 30 \
    --label C2_stage2_slow --total 10000 --concurrency 200 --settle-max-wait 600

cp .workbuddy/jar-batch.jar target/short-url-server-1.0.0.jar
python tools/run_load_test_batch.py --simulate-latency-ms 30 \
    --label D2_batch_slow --total 10000 --concurrency 200 --settle-max-wait 600
```

结果写入 `docs/load_results/*.result.txt`，含 QPS、延迟分位数、落库耗时、`DbUpdateCount`、`DeltaRatio`、`VERDICT` 等字段。闸门不通过时脚本退出码为 2，并在结果文件 `problems` 段落写明原因。

### 6.7 补测：加入同步模式基线后的三方对照（证伪第四章预期）

第四章曾预期"批量聚合后吞吐立刻反超同步"。为验证这句话，补测同步模式基线 E2（阶段2 jar + `-Dshort-url.click.async-enabled=false`，0ms，10万/500并发，PASS，落库率 100.00%），与 A2/B2 构成**同一场**三方对照：

| 模式 | QPS | p50 | p95 | p99 | 落库耗时 | DB UPDATE |
|---|---|---|---|---|---|---|
| **E2 同步写库（阶段1 路径）** | **9227.9** | **45.64** | **89.37** | **104.15** | 6.4s | 99500 |
| A2 MQ 逐条消费（阶段2） | 4672.8 | 100.51 | 199.74 | 252.38 | 52.2s | 99500 |
| B2 MQ 批量聚合 | 4224.6 | 119.34 | 196.60 | 249.93 | 7.7s | **995** |

**结论：原预期被证伪。** 批量聚合的 QPS 只有同步模式的 **45.8%**，且比 MQ 逐条还低 9.6%；p50/p95/p99 三项均由同步模式大幅领先。

**为什么"消费提速"没有换来 QPS？**
- 批量聚合让消费效率提升约 100 倍（落库耗时 52.2s → 7.7s），但 QPS 纹丝不动 → 说明**队列堆积从来没有拖累过 QPS**。
- 原因：单条 `ClickMessage` 约 60 字节，10 万条合计约 6MB，远未触及 RabbitMQ 默认内存高水位（物理内存 40%，本机数 GB）→ **broker 流控从未触发**，publisher 也就从未被阻塞。
- 真正的 QPS 天花板在**发布侧**：JSON 序列化 + 发往 broker 的一跳网络往返 + publisher-confirm 回执。这部分是每条请求都逃不掉的固定开销，批量聚合不解决它。

**仍需注意的边界**：30ms 慢下游组（C2 → D2）观测到 QPS +6.8%，是批量聚合在 QPS 上唯一的正向信号——那是真正存在持续堆积的场景。说明该优化在"消息体较大 / 堆积足以触发流控"时可能有效，但幅度远达不到"反超同步"。

> 一句话：**批量聚合是"数据库写压力"与"一致性收敛速度"的优化，不是接口吞吐优化。** 想提升 QPS，方向在发布侧（关闭 publisher-confirm 等），见第四章第 3 项。

---

## 七、备注（原第六节顺延）

- 压测期间 Redis 需确认可写（若出现 `MISCONF Redis is configured to save RDB snapshots...`，可执行 `redis-cli CONFIG SET stop-writes-on-bgsave-error no` 绕过，或把 Redis `CONFIG SET dir` 指向一个可写目录）。
- 部分运行出现个位数 ~0.5% 连接重置（`ERR`），为压测客户端 asyncio TCP 关闭回调偶发 WinError 10054，成功响应均为 302，不影响结论。
- 测试后 `click_count` 累计偏大属正常（多次压测叠加 + MQ 最终一致），不影响接口与结论。
- 压测前务必确认**没有竞争消费者**：任何额外运行的 short-url 实例（如 IDE 里启着的）都会与压测后端抢同一个队列，同时消费消息，导致落库耗时与 UPDATE 计数失真。
- 本机 RabbitMQ 未启用 management 插件，15672 端口无监听，管理台 HTTP API 不可用；队列深度改用 MySQL `click_count` 稳态判定，死信深度不可观测（由落库增量兜底）。
- DB 写次数取自 MySQL 全局计数器 `Com_update` 的前后差值（已验证静默期零漂移），压测期间通过 `-Dmybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl` 关闭逐条 SQL 日志，避免日志 I/O 成为不公平干扰项。
- 第六章四组（A2/B2/C2/D2）原始结果已归档于 `docs/load_results/*.result.txt`；更早的四组（A/B/C/D）已作废，仅作问题留档保留。
- 阶段4 压测期间**必须关闭 MySQL 查询缓存**（`SET GLOBAL query_cache_type=OFF`），原因见 8.7 节。

---

## 八、阶段4 缓存穿透 / 击穿治理 对照测试

### 8.1 要解决的问题

阶段1~3 的跳转链路（对应 `mode=none`）有两个可以被压测量化出来的缺陷，都在 `ShortUrlServiceImpl.getOriginalUrl()`：

| # | 位置 | 问题 |
|---|------|------|
| 穿透 | 缓存未命中后查 DB，不存在时**不写空值** | 每个不存在的短码都是一次 DB 查询。被刷 10 万个随机短码 = 10 万次 DB 查询，全部落空 |
| 击穿 | 缓存未命中无任何重建互斥 | 热 key TTL 到期的瞬间，所有并发请求同时回源、同时回写 |

### 8.2 治理方案：三层防护

```
跳转请求
  → ① 短码格式校验    长度 1~10 且只含 62 进制字符集，非法输入连布隆都不用问
  → ② 布隆过滤器      判"一定不存在"直接 404，零 DB 查询（挡穿透）
  → ③ 缓存策略        mutex 互斥锁重建 / logical-expiry 逻辑过期（挡击穿）
  → ④ 空值缓存        布隆误判漏过来的非法短码，查完 DB 后写空值标记，只查一次
```

两种击穿方案都实现了，靠配置切换（`short-url.cache-guard.mode`），便于同场对照：

- **mutex**：缓存未命中 → 抢分布式锁 → 只有抢到锁的线程回源，其余线程短暂等待后双重检查。强一致，代价是未抢到锁的请求有等待延迟。
- **logical-expiry**：缓存不靠 Redis TTL 过期，把过期时间存在 value 里由应用判断。过期后抢到锁的请求提交异步重建并**立即返回**，其余请求继续拿旧值 —— 全链路零等待。本项目 `original_url` 创建后几乎不变，"旧值"与"新值"实际是同一个，返回旧值没有业务风险。

> **2026-09-01 会话更新**：本节原写"分布式锁与布隆过滤器均为自研"，本次会话已把布隆换成 Redisson `RBloomFilter`（Redis Bitmap 共享持久，n=100 万 / p=1%），**分布式锁仍自研**（SETNX + Lua 原子释放防误删）。详见 `PROJECT_HISTORY.md` 第十九章。

~~分布式锁与布隆过滤器均为自研（基于 `StringRedisTemplate`），未引入 Redisson。~~ → 当前：布隆过滤器用 Redisson `RBloomFilter`，分布式锁仍自研（Redis SETNX + Lua）。

### 8.3 测试方法

三种模式跑**同一份 jar**，靠启动参数切换。代码路径完全不同（不是开关切死代码），对照有效：

```
none            阶段1~3 原逻辑，未做任何改动，作为基线
mutex           互斥锁重建
logical-expiry  逻辑过期 + 异步重建
```

三组场景：

| 场景 | 做法 | 看的指标 |
|------|------|----------|
| 穿透组 | 5000 个随机生成的合法格式短码（几乎必然不存在），并发 300 | 打到 DB 的次数 |
| 击穿组 | 删掉热 key 缓存后，500 并发同时打同一个存在的短码 | 打到 DB 的次数、延迟 |
| 逻辑过期组 | 缓存已逻辑过期**但仍存在**时，500 并发打同一个短码 | 打到 DB 的次数、延迟 |

取数双保险：
1. 应用内计数器（`GET /api/short-url/cache-guard/metrics`）
2. MySQL 全局 `Com_select` 增量 —— 独立于应用，防止计数器本身写错

### 8.4 数据结果

#### 8.4.1 穿透组：5000 个随机不存在的短码，并发 300

| 模式 | 打到 DB 次数 | 布隆拦截 | `Com_select` 增量 | QPS | p50 (ms) | p99 (ms) |
|------|-------------:|---------:|------------------:|----:|---------:|---------:|
| none | **5000** | 0 | 5001 | 2181.9 | 57.15 | 124.67 |
| mutex | **0** | 5000 | 1 | 2173.4 | 62.15 | 138.03 |
| logical-expiry | **0** | 5000 | 1 | 2164.9 | 58.21 | 125.10 |

应用内计数器与 MySQL `Com_select` 完全吻合（5000↔5001、0↔1），互相印证。

**结论：穿透被完全消除，DB 查询 5000 → 0。**

#### 8.4.2 击穿组：清掉热 key 缓存后，500 并发打同一个短码

| 模式 | 打到 DB 次数 | 缓存命中 | 抢锁成功 | QPS | p50 (ms) | p99 (ms) | max (ms) |
|------|-------------:|---------:|---------:|----:|---------:|---------:|---------:|
| none | **14** | 486 | — | 1504.6 | 129.20 | 202.56 | 215.05 |
| mutex | **1** | 448 | 1 | 1920.9 | 100.64 | 144.09 | 155.46 |
| logical-expiry | **1** | 440 | 1 | 1924.6 | 52.11 | 113.22 | 127.77 |

**结论：DB 查询 14 → 1（-92.9%），同时 QPS 从 1504.6 提升到 1920.9（+27.7%）** —— 提升来自消除回源等待，而非 DB 变快。

#### 8.4.3 逻辑过期组：缓存已逻辑过期但仍存在，500 并发

| 模式 | 打到 DB 次数 | 逻辑过期命中 | 异步重建 | QPS | p50 (ms) | p99 (ms) | max (ms) |
|------|-------------:|-------------:|---------:|----:|---------:|---------:|---------:|
| logical-expiry | **1** | 11 | 1 | 2051.3 | 18.48 | **36.26** | 41.12 |

500 个请求中只有 11 个撞上"已过期"状态，其余 489 个命中的是异步重建完成后刷新的新值 —— 重建在毫秒级完成，几乎没有请求感知到。

### 8.5 两种击穿方案怎么选

**不能拿 8.4.2 的 mutex 与 8.4.3 的 logical-expiry 直接横向比**，因为起始状态不同：

- 8.4.2 模拟的是 **mutex 模式下的失效瞬间**：Redis TTL 到期，缓存彻底消失 → 冷启动尖峰
- 8.4.3 模拟的是 **logical-expiry 模式下的失效瞬间**：缓存还在，只是内容变陈

各自的"失效瞬间"表现：

| | mutex（TTL 到期，缓存消失） | logical-expiry（逻辑过期，缓存仍在） |
|---|---|---|
| DB 查询 | 1 | 1 |
| p99 | 144.09 ms | **36.26 ms** |
| 请求是否等待 | 未抢到锁的线程要等待 | 全部零等待 |

**这才是逻辑过期的真正价值**：它让"缓存彻底消失"这件事在生产中几乎不再发生。mutex 模式下每 7 天 TTL 到期必然来一次冷启动尖峰；logical-expiry 模式下缓存永远是热的一一它只会变陈，而变陈的代价是 1 次异步重建 + 零请求等待。

代价是可能短暂返回旧值。本项目 `original_url` 创建后不变，可以接受；库存、余额这类强一致场景不适用。

### 8.6 单元测试实测数据

项目此前**零测试**，阶段4 补了 11 个（`src/test/java/com/shorturl/`），全部通过：

```
RedisBloomFilterTest (6 个)      CacheGuardTest (5 个)
  实测误判率 = 0.005% (1/20000)，配置值 1%
  位数组 = 95851 bit (11 KB)，hash 函数 = 7 个       [n=1万, p=1%]
  位数组 = 9585059 bit (1170 KB)，hash 函数 = 7 个   [n=100万, p=1%]
  穿透测试: 请求 300, 打到DB 0, 布隆拦截 300
  击穿测试: 50 并发, 打到DB 1, 抢锁成功 1
  空值缓存: 首次 DB 查询 0, 后续 20 次 DB 查询 0
```

参数推导与理论公式完全吻合：`m = -n·ln(p)/(ln2)²`，`k = m/n·ln2`。

**误判率 0.005% 远低于配置的 1%，原因是布隆过滤器只填了 20%（插入 2000 / 预期 10000）。误判率随填充率上升**——位数组按预期插入量预留，实际填充越满，误判率越接近配置值。这一点在容量规划时要注意：不要按当前数据量配置 `expected-insertions`，要按未来规模。

### 8.7 方法论备注（两个踩过的坑）

**① MySQL 查询缓存会掩盖 DB 压力，压测前必须关掉。**

本机 MySQL 5.5 默认 `query_cache_type=ON`（15MB）。击穿组是"同一个短码被反复查"，相同 SQL 会直接命中查询缓存，`Com_select` 不计数（只记 `Qcache_hits`）。开着它等于给基线组开外挂：DB 明明被打了 14 次，`Com_select` 只涨 5 次，会**严重低估治理前的真实压力，让对照失真**。

脚本已在开始时 `SET GLOBAL query_cache_type=OFF`、结束时恢复 `ON`。

**② QPS 与延迟数字受 Python 客户端限制，仅供参考；DB 查询次数才是硬指标。**

压测客户端是 Python `urllib` + 线程池，与阶段1~3 用的是同一套。它的并发能力上限决定了 QPS 天花板（约 2000~2500），远低于服务端的真实能力。延迟里也混入了客户端侧的排队时间，分布长尾偏重。

因此本章的结论**只用 DB 查询次数和计数器做定性判断**，QPS/延迟只作为同场对比的相对参考，不能当作服务端的绝对性能指标。

### 8.8 复现命令

```bash
# 全量三组对照（约 1.5 分钟，脚本会自动启停后端、清 Redis、开关查询缓存）
python tools/run_cache_guard_test.py \
  --total 5000 --concurrency 300 \
  --hot-requests 500 --hot-concurrency 300 \
  --logical-expire 3

# 小样本冒烟（约 40 秒）
python tools/run_cache_guard_test.py --smoke --modes none,mutex

# 只跑某几种模式
python tools/run_cache_guard_test.py --modes none,logical-expiry
```

```bash
# 单元测试（需本地 Redis + MySQL 可用）
mvn test -Dtest='RedisBloomFilterTest,CacheGuardTest'
```

```bash
# 手动切模式
java -jar short-url-server/target/short-url-server-1.0.0.jar \
  --short-url.cache-guard.mode=logical-expiry

# 实时看计数器
curl http://localhost:8082/api/short-url/cache-guard/metrics
curl -X POST http://localhost:8082/api/short-url/cache-guard/metrics/reset
```

