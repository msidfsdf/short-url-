# 短链管理系统 - 会话历史档案

> 本文档记录项目从0到1的完整搭建过程，用于在新会话中快速恢复上下文。

---

## 一、项目背景与决策

### 1.1 为什么选短链系统（而不是商城/外卖）

用户找开发实习，需要一个**不烂大街**的项目。对比分析后选择短链系统：

| 对比维度 | 商城/外卖系统 | 短链系统 |
|---|---|---|
| 业务复杂度 | 极高（订单/库存/支付） | 极低（生成+跳转） |
| 核心难点 | 业务建模、状态机、事务 | 高并发、缓存、存储 |
| 面试讲点 | "做了哪些功能" | "解决了什么技术问题" |
| 烂大街程度 | ⭐⭐⭐⭐⭐ | ⭐⭐ |
| CRUD 占比 | 80% | 30% |
| 扩展性 | 业务拆分 | 天然适合技术扩展 |

**核心理念**：商城是"业务项目"，短链是"技术项目"。功能少但每个点都能往深挖。

### 1.2 整体技术演进路线

```
阶段1（已完成）: SpringBoot + Redis + MySQL + Vue    → 跑通核心功能
阶段2（已完成）: + RabbitMQ                          → 异步统计、削峰、死信兜底
阶段3（待做）  : + SpringAI                          → 智能生成标题/数据分析
阶段4（待做）  : + 缓存/并发进阶(布隆/多级缓存/号段)  → 面试高频考点
```

---

## 二、核心技术实现思路

### 2.1 五个核心问题

| 问题 | 方案 |
|---|---|
| 短链码生成 | **自增ID + 62进制转换**（6位短码支持568亿链接） |
| 短链跳转 | HTTP **302** 临时重定向（能统计点击量，不缓存） |
| 缓存设计 | Cache Aside 模式，Redis key: `short:url:{code}` |
| 点击统计 | 阶段1同步 UPDATE（瓶颈）→ **阶段2 已用 MQ 异步削峰**（发消息 + 消费者异步累加，失败进死信队列） |
| 过期清理 | 定时任务 + 惰性删除 |

### 2.2 短链码生成方案对比

| 方案 | 优 | 劣 | 是否选用 |
|---|---|---|---|
| A. Hash算法 | 无需存储映射 | 会冲突 | ❌ |
| **B. 自增ID+62进制** | 无冲突、可预测、生成快 | 依赖自增ID | ✅ 当前 |
| C. 预生成号段 | 性能极高 | 实现复杂 | 🔜 高并发时升级 |

### 2.3 两阶段写入逻辑

```
阶段1: INSERT (short_code=null) → 拿到自增ID 比如 1000000
阶段2: ID 转 62 进制 → UPDATE short_code = '4c9g'
```

这是后续多次遇到问题的关键点。

---

## 三、项目目录结构

```
C:\Users\LENOVO\Desktop\short-url\           ← 项目根目录
├── sql\
│   ├── init.sql                           ← 建库建表脚本
│   └── fix_short_code_null.sql            ← 修复SQL
├── short-url-server\                      ← 后端 (SpringBoot, 端口8081)
│   ├── pom.xml
│   ├── test.http                          ← IDEA HTTP Client 测试
│   └── src\main\
│       ├── java\com\shorturl\
│       │   ├── ShortUrlApplication.java
│       │   ├── common\
│       │   │   ├── R.java                 ← 统一返回
│       │   │   └── GlobalExceptionHandler.java
│       │   ├── config\
│       │   │   ├── MybatisPlusConfig.java  ← 分页插件
│       │   │   └── MyMetaObjectHandler.java ← 自动填充时间
│       │   ├── controller\
│       │   │   └── ShortUrlController.java ← 3个接口
│       │   ├── entity\
│       │   │   ├── po\ShortUrl.java
│       │   │   ├── dto\ShortUrlCreateDTO.java
│       │   │   └── vo\ShortUrlVO.java
│       │   ├── mapper\
│       │   │   └── ShortUrlMapper.java
│       │   ├── service\
│       │   │   ├── IShortUrlService.java
│       │   │   └── impl\ShortUrlServiceImpl.java  ← 核心逻辑
│       │   └── utils\
│       │       └── Base62Utils.java        ← 62进制工具
│       └── resources\
│           └── application.yml             ← 配置文件
└── short-url-web\                         ← 前端 (Vue3, 端口5173)
    ├── package.json
    ├── vite.config.js                     ← 含 /api 代理到 8081
    ├── index.html
    └── src\
        ├── main.js
        ├── App.vue
        ├── router\index.js
        ├── api\
        │   ├── request.js                 ← axios 封装
        │   └── shortUrl.js                ← 短链接口
        └── views\
            ├── CreateView.vue             ← 创建页
            └── ListView.vue               ← 列表页
```

---

## 四、技术栈与版本

### 4.1 后端
- **JDK**: 17（路径 `C:\Users\LENOVO\Desktop\jdk-17.0.18+8`）
- **Spring Boot**: 3.3.5
- **MyBatis-Plus**: 3.5.9（含 jsqlparser 3.5.9）
- **MySQL**: 8.x（root/root，端口3306，库名 `short_url`）
- **Redis**: 默认 localhost:6379，无密码
- **Hutool**: 5.8.36
- **Lombok**: 1.18.36

### 4.2 前端
- **Node**: v16.17.1（⚠️ 版本偏低，vite 5 不兼容，已降级到 vite 4）
- **Vue**: 3.4.x
- **Vite**: 4.5.x（从 5.x 降级）
- **Element Plus**: 2.4.x（从 2.7 降级）
- **vue-router**: 4.3.x
- **axios**: 1.7.x

### 4.3 启动信息
- **后端端口**: 8081
- **前端端口**: 5173（已配置 /api 代理转发到后端）
- **短链域名前缀**: `http://localhost:8081/`

---

## 五、数据库设计

### 5.1 库表
```sql
CREATE DATABASE IF NOT EXISTS short_url DEFAULT CHARACTER SET utf8mb4;

CREATE TABLE t_short_url (
    id           BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
    short_code   VARCHAR(10)   NULL     COMMENT '短链码(两阶段写入,先INSERT拿ID再UPDATE)',
    original_url VARCHAR(500)  NOT NULL,
    click_count  BIGINT        NOT NULL DEFAULT 0,
    expire_time  DATETIME      NULL,
    create_time  DATETIME      NULL DEFAULT NULL,
    update_time  DATETIME      NULL DEFAULT NULL,
    UNIQUE KEY uk_short_code (short_code),
    KEY idx_original_url (original_url(50))
) ENGINE=InnoDB AUTO_INCREMENT=1000000 DEFAULT CHARSET=utf8mb4;
```

**关键设计**：
- `AUTO_INCREMENT=1000000` → 起始ID从100万开始，保证短码至少4位
- `short_code` 允许 NULL（两阶段写入）
- `create_time/update_time` 用 `DATETIME NULL`（不用TIMESTAMP，避免MySQL版本兼容问题）

---

## 六、核心接口

| 接口 | 方法 | 说明 |
|---|---|---|
| `POST /api/short-url/create` | POST | 生成短链，入参 `{originalUrl, expireSeconds}` |
| `GET /api/short-url/list?current=1&size=10` | GET | 分页查询 |
| `GET /{shortCode}` | GET | 302跳转到原URL |

---

## 七、遇到的问题与解决方案（重要！）

### 问题1: MySQL 建表报错 1067
```
[42000][1067] Invalid default value for 'create_time'
```
**原因**: MySQL 严格模式下 DATETIME 不能用 `CURRENT_TIMESTAMP` 作默认值（老版本限制）
**解决**: 把 `TIMESTAMP DEFAULT CURRENT_TIMESTAMP` 改为 `DATETIME NULL`，时间戳由 MyBatis-Plus `MyMetaObjectHandler` 在Java层自动填充

### 问题2: MySQL 建表报错 1293
```
[HY000][1293] Incorrect table definition; there can be only one TIMESTAMP column with CURRENT_TIMESTAMP
```
**原因**: 老版本 MySQL 限制一张表只能有一个 TIMESTAMP 列用 CURRENT_TIMESTAMP
**解决**: 同问题1，全部用 DATETIME NULL + Java层填充

### 问题3: 编译报错找不到 PaginationInnerInterceptor
```
java: 找不到符号 类 PaginationInnerInterceptor
```
**原因**: MyBatis-Plus 3.5.9 把分页插件拆到了独立模块
**解决**: pom.xml 添加依赖
```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-jsqlparser</artifactId>
    <version>3.5.9</version>
</dependency>
```

### 问题4: YAML 解析报错
```
ScannerException: mapping values are not allowed here
in 'reader', line 39, column 26: cache-prefix: short:url:
```
**原因**: 值里包含冒号（如 `short:url:`）YAML 会误解析为嵌套map
**解决**: 给含冒号的值加双引号
```yaml
domain: "http://localhost:8081/"
cache-prefix: "short:url:"
```

### 问题5: 生成短链报错 Field 'short_code' doesn't have a default value
**原因**: 两阶段写入逻辑下，INSERT时 short_code 为 null，但列定义为 NOT NULL
**解决**: 执行 ALTER TABLE
```sql
ALTER TABLE t_short_url MODIFY COLUMN short_code VARCHAR(10) NULL;
```

### 问题6: 前端 vite 5 不兼容 Node 16
```
npm WARN EBADENGINE Unsupported engine
package: 'vite@5.4.21', required: { node: '^18.0.0 || >=20.0.0' }, current: { node: 'v16.17.1' }
```
**解决**: 降级 vite 4 + element-plus 2.4
```json
"@vitejs/plugin-vue": "^4.5.0",
"vite": "^4.5.0",
"element-plus": "^2.4.0"
```

---

## 八、阶段1 MVP 已完成的功能

- ✅ 后端项目骨架搭建
- ✅ 建库建表 + 数据库修复
- ✅ 实体类 / DTO / VO / Mapper / Service / Controller
- ✅ 62进制转换工具类
- ✅ Redis 缓存读写（Cache Aside）
- ✅ MyBatis-Plus 分页插件 + 自动填充时间
- ✅ 全局异常处理 + 统一返回结构
- ✅ 三个核心接口（生成/查询/跳转）
- ✅ 前端 Vue3 项目骨架
- ✅ axios 封装 + 全局响应拦截
- ✅ 创建页（表单 + 结果展示 + 复制按钮）
- ✅ 列表页（表格 + 分页 + 复制/跳转）
- ✅ 前后端联调跑通

---

## 九、下一步扩展计划

### 阶段2: 引入 RabbitMQ（异步削峰）✅ 已完成
- 跳转点击事件 → 发到 MQ（Direct 交换机 + 正常队列）→ 消费者异步 UPDATE（解耦统计写库，削峰）
- 手动 ACK + 死信队列（DLX）兜底失败消息，避免失败消息无限重入队阻塞队列
- 消息体 `ClickMessage{shortCode, timestamp}`，JSON 序列化
- **关键设计**：MQ 不可用时只记日志、绝不阻塞 302 跳转（统计是副作用，可丢失不可挡）
- 见 [docs/阶段2版本说明书.md](file:///c:/Users/LENOVO/Desktop/short-url/docs/阶段2版本说明书.md)

### 阶段3: 引入 SpringAI（智能化）
- AI 自动生成短链标题/描述
- AI 分析点击趋势生成报表
- AI 智能推荐热门短链

### 阶段4（可选进阶）: 解决面试高频问题
- **布隆过滤器** 防止缓存穿透
- **互斥锁** 解决缓存击穿
- **TTL加随机值** 防止缓存雪崩
- **号段模式** 替代自增ID，提升并发能力
- **本地缓存 (Caffeine)** 多级缓存架构

### 附：微服务拆分评估（当前不落地）
- **结论（2026-08-27 与用户讨论确认）**：当前系统规模（3 个接口、单机即可支撑、单人开发）远未到微服务临界点，提前拆分是过度设计，会带来分布式事务、注册发现、链路追踪、运维等额外成本。
- **正确定位**：保留单体，但把「读」与「写副作用」解耦（阶段2 用 MQ 实现）。等跳转与统计的扩缩容需求分化时，再把消费端拆为独立服务，用 MQ 做边界。
- 简历/面试中体现「理解微服务拆分时机与边界」，**不实际落地**。

---

## 十、用户偏好与环境备忘

- **沟通语言**: 中文
- **操作系统**: Windows (PowerShell 环境)
- **IDE**: IntelliJ IDEA 2025.1
- **MySQL 密码**: root
- **项目根目录**: `C:\Users\LENOVO\Desktop\short-url\`
- **用户意图**: 找开发实习，需要技术深度项目
- **用户偏好**: 能自动执行的操作就直接执行，不要让用户手动操作；但**不要擅自结束用户正在手动运行的进程/服务实例**（曾因我 auto-kill 用户手动起的 Redis 实例被提醒）
- **用户熟悉技术栈**: Spring Cloud、Spring Boot、Nacos、MyBatis-Plus、Maven、RabbitMQ、Feign、Redis

### 10.1 中间件实际安装位置（2026-08-27 实测）

| 组件 | 安装路径 | 启动方式 | 连接信息 | 备注 |
|---|---|---|---|---|
| **JDK 17** | `C:\Users\LENOVO\Desktop\jdk-17.0.18+8` | — | — | 编译/运行后端用 |
| **Maven 3.9.9** | `C:\Users\LENOVO\.tools\maven\apache-maven-3.9.9` | — | — | Git Bash 下 IDEA 自带 Maven 路径有问题，改用此独立 Maven（调用方式见 12.12）|
| **Erlang 27.3.4.16** | `D:\rabbitmq\Erlang OTP` | — | — | RabbitMQ 的运行引擎，装完不用管 |
| **RabbitMQ 4.3.5** | `D:\rabbitmq\rabbitmq_server-4.3.5` | **Windows 服务，开机自启(Automatic)，已验证 Running** | `localhost:5672` / `guest`/`guest` | 管理后台 `http://localhost:15672`（需 `rabbitmq-plugins enable rabbitmq_management`）|
| **Redis 3.2.100** | `D:\Redis-x64-3.2.100\Redis-x64-3.2.100` | **已注册 Windows 服务(StartType=Automatic)，但当前用户手动起实例在跑**（PID 占 6379，服务本身 Stopped） | `localhost:6379`，无密码 | 服务与手动实例抢 6379 端口，二者只能活一个；切纯服务模式需先关手动终端再 `redis-server --service-start` |

> 安装包（.exe）原下载于 `tools/installers/`，装完后已删除；该目录已在 `.gitignore` 忽略。

---

## 十一、给新会话的快速恢复指南

如果你在新会话里要继续这个项目，只需告诉 AI：

> 我在做一个短链管理系统，项目位于 `C:\Users\LENOVO\Desktop\short-url\`，已完成阶段1 MVP（SpringBoot+Redis+MySQL+Vue3），现在要做阶段X...

然后根据需要选择：
- 做 **阶段2 RabbitMQ**：已完成，点击统计已改为 MQ 异步（见 docs/阶段2版本说明书.md）
- 做 **阶段3 SpringAI**：让 AI 帮你集成 AI 能力
- **优化现有代码**：让 AI 帮你加布隆过滤器/多级缓存/号段等（阶段4 进阶）

完整历史细节见本文件。

---

## 十二、本次会话补充技术要点（2026-08-27）

> 以下内容记录用户在学习/复盘阶段提出的问题与确认结论，便于后续面试讲解与新会话恢复。

### 12.1 短链跳转实现原理

**核心流程**：
1. 浏览器访问 `http://localhost:8081/{shortCode}`
2. Spring MVC 根据 `@GetMapping("/{shortCode}")` 路由到 `ShortUrlController.redirect()`
3. Service 层查 Redis（未命中查 DB → 回写缓存）拿到原始 URL
4. Controller 设置 `response.setStatus(302)` + `response.setHeader("Location", originalUrl)`
5. 浏览器收到 302 状态码后自动跳转到原始 URL

**为什么用 302 而不是 301**：
- 301 永久重定向：浏览器会缓存，导致后续点击不再访问服务端，**无法统计点击量**
- 302 临时重定向：每次都回服务端，能统计点击，代价是每次都查缓存/DB

**关键代码**（[ShortUrlController.java](file:///c:/Users/LENOVO/Desktop/short-url/short-url-server/src/main/java/com/shorturl/controller/ShortUrlController.java)）：
```java
@GetMapping("/{shortCode}")
public void redirect(@PathVariable String shortCode, HttpServletResponse response) throws IOException {
    String originalUrl = shortUrlService.getOriginalUrl(shortCode);
    if (originalUrl == null) {
        response.setStatus(404);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write("短链不存在或已过期");
        return;
    }
    response.setStatus(302);
    response.setHeader("Location", originalUrl);
}
```

### 12.2 Spring MVC 参数注入机制

用户曾困惑 `@PathVariable` 和 `HttpServletResponse` 这两个参数"从哪里来"。

| 参数类型 | 注入方式 | 说明 |
|---|---|---|
| `@PathVariable String shortCode` | **从 URL 路径提取** | `@GetMapping("/{shortCode}")` 中的 `{shortCode}` 占位符 → 方法的 `@PathVariable` 参数 |
| `HttpServletResponse response` | **框架自动注入** | Servlet 容器创建并绑定到 TCP socket 输出流，Spring 自动传入方法，**不能 new** |

**注解的作用**：`@GetMapping("/{shortCode}")` 省略的是"主机+端口"部分（由 server.port 决定），完整路径是 `http://localhost:8081/{shortCode}`。浏览器访问任意以 8081 开头、匹配该 pattern 的 URL，都会进入此方法。

### 12.3 HTTP 请求/响应消息结构

**HTTP 请求**：
- **请求行**：`GET /4c9g HTTP/1.1`（方法 + URL + 协议版本）
- **请求头**：`Host: localhost:8081`、`User-Agent`、`Accept` 等元数据。**注意：不是所有头都必填**，只有当有请求体时 `Content-Type` 和 `Content-Length` 才必填
- **请求体**：业务数据，GET 通常无，POST/PUT/PATCH 才有

**HTTP 响应**：
- **状态行**：`HTTP/1.1 302 Found`（版本 + 状态码 + 原因短语）
- **响应头**：`Location: https://原URL`、`Content-Type` 等
- **响应体**：302 跳转通常无 body，浏览器拿到 Location 头就自动跳转

### 12.4 前端架构与各组件职责

**技术栈**：Vue 3 + Vite 4 + Element Plus 2.4 + Vue Router 4 + axios 1.7

| 组件 | 类比 | 职责 |
|---|---|---|
| **Vite** | Maven（构建）+ 开发服务器 | 打包 + HMR 热更新开发服务器 |
| **Vue 3** | 前端 UI 框架 | 用 `.vue` 单文件组件（template+script+style）替代 html |
| **Element Plus** | UI 组件库 | 提供按钮/表格/分页等现成组件 |
| **Vue Router** | 前端路由 | SPA 单页应用，URL 切换不刷新页面 |
| **axios** | HTTP 客户端 | 负责前后端通信，封装在 `api/request.js` 做统一错误处理 |

**`src` 目录结构**：
```
src/
├── main.js            # 入口：挂载 Vue + Element Plus + 路由
├── App.vue            # 根组件：含 <router-view/>
├── router/index.js    # 路由表：/create /list
├── api/
│   ├── request.js     # axios 实例 + 拦截器（处理 R 对象的 code）
│   └── shortUrl.js    # 接口封装：哪个页面调用哪个后端方法
└── views/
    ├── CreateView.vue # 创建短链页
    └── ListView.vue   # 短链列表页
```

**`api` 文件的作用**：只管"哪个页面操作要调哪个后端接口"，不管 UI 交互（点击事件在 `.vue` 组件里写）。

### 12.5 短码为什么用 62 进制

**字符集**：`0-9` (10) + `a-z` (26) + `A-Z` (26) = **62 个 URL 安全字符**

**容量计算**：
- 4 位短码：62⁴ = 1477 万
- 6 位短码：62⁶ = **568 亿**（足够绝大多数业务）

**对比哈希算法（已否决）**：
- 哈希的通病：不同长链可能生成相同短码（碰撞概率非0），需要额外查 DB 去重
- 自增 ID + 62 进制：天然唯一，**无碰撞**，且短码可预测（同一个长链生成的 ID 相同 → 短码相同）

### 12.6 Redis 在本项目中的用法

**数据结构**：String 类型（最简单的 key-value）

| 维度 | 值 |
|---|---|
| Key | `short:url:{shortCode}`（前缀来自 `application.yml` 的 `short-url.cache-prefix`） |
| Value | 原始长 URL |
| TTL | 7 天（`short-url.cache-ttl-days`） |

**Cache Aside 模式**：
- **读**：先查 Redis → 命中直接返回；未命中查 DB → 回写 Redis → 返回
- **写**（创建短链时）：先写 DB → 再写 Redis

**为什么不用 Hash 等复杂结构**：短链场景只需"短码 → 原URL"一对一映射，String 足够，性能最高。

### 12.7 并发压力分析

| 操作 | 频率 | 压力点 | 当前方案 | 优化计划 |
|---|---|---|---|---|
| 跳转（查原始 URL） | **极高**（每次点击） | Redis 读（缓存未命中打 DB） | Cache Aside 缓存 | 已够用 |
| 点击统计（UPDATE） | **极高**（每次点击） | DB 写压力 | 阶段1 同步 UPDATE | **✅ 阶段2 已解决**：跳转只发 `ClickMessage` 到 MQ → 消费者异步 `click_count+1`，写库与请求路径解耦，天然削峰 |

**核心瓶颈（已解决）**：阶段1 跳转流程里的"同步 UPDATE 点击数"是最大性能瓶颈，每次点击都打一次 DB 写；阶段2 用 RabbitMQ 把该写入移出关键路径（详见 [docs/阶段2版本说明书.md](file:///c:/Users/LENOVO/Desktop/short-url/docs/阶段2版本说明书.md)）。

### 12.8 域名访问限制

**当前问题**：`application.yml` 中 `short-url.domain: "http://localhost:8081/"` 导致生成的短链只能本机访问。

**解决方案**（按场景选）：
- 局域网测试：改成 `http://<本机IP>:8081/`
- 临时外网访问：用 ngrok / natapp 内网穿透
- 正式部署：买域名 + 服务器 + 备案

### 12.9 阶段1 版本说明书（草稿）

用户要求撰写"版本说明书"用于写入项目，内容应包含：
1. **本阶段用到的技术**：每个技术**明确写出解决了什么问题**（不是简单罗列）
2. **现阶段会遇到的问题**：列出留给下阶段解决的痛点（不展开方案）
3. **工作流程**：描述要**简单**（用户明确要求"工作流程稍微写简单一点"）

> 该版本说明书为草稿状态，尚未正式写入项目。下个会话可继续完善并落盘。

### 12.10 阶段2 实现记录（2026-08-27）

**技术选型理由（核心）**：跳转请求内含两件事，性质不同——
- **查原 URL**：硬依赖，必须当场拿到返回值才能发 302，无法用 MQ 优化（MQ 发完不拿结果）。
- **点击 +1**：纯副作用，与响应无关，延后 200ms 用户无感。故"读留在线，写丢给 MQ"。

**改动清单**：
- 新增依赖：`spring-boot-starter-amqp`（pom.xml）
- 新增文件：
  - `mq/ClickMessage.java` —— 消息体 `{shortCode, timestamp}`
  - `config/RabbitMQConfig.java` —— Direct 交换机 + 正常队列(带死信参数) + DLX 交换机/死信队列 + 绑定 + `Jackson2JsonMessageConverter`
  - `mq/ClickProducer.java` —— `convertAndSend` 发消息，异常仅记日志不抛（不阻塞跳转）
  - `mq/ClickCountConsumer.java` —— `@RabbitListener(ackMode=MANUAL)`，成功 `basicAck`，失败 `basicNack(requeue=false)` 进死信
- 改造 `ShortUrlServiceImpl`：
  - `getOriginalUrl()` 两处同步 `incrementClickCount` 改为 `clickProducer.sendClick(shortCode)`
  - 原 `incrementClickCount` 改为 `recordClick(shortCode)`（public，供消费者调用），`click_count = click_count + 1` 原子更新
- `application.yml`：新增 `spring.rabbitmq`（localhost:5672/guest）与 `short-url.mq.*` 队列/交换机常量

**验证**：`mvn compile` BUILD SUCCESS；`spring-boot:run` 成功启动（Started in 3.379s，消费者容器 `acknowledgeMode=MANUAL`）；broker 未装时仅有 `Connection refused`，应用照常启动，证明跳转不被 MQ 阻塞。

**下一步可演进**：消费者从"每条消息一次 UPDATE"升级为"批量聚合 +1"以进一步削峰；ClickMessage 的 timestamp 可用于阶段4 点击趋势分析。

### 12.11 QPS 压测（已完成，2026-08-27 晚）

**目的**：量化"阶段2 MQ 异步"相比"阶段1 同步 UPDATE"的收益。

**采用办法**：**办法 B（开关）已落地**，未用 git stash 切 commit。在 `ShortUrlServiceImpl` 增加 `@Value short-url.click.async-enabled`（默认 true），用统一入口 `recordClickEvent()` 按开关走 MQ(`sendClick`) 或同步(`recordClick`)；`application.yml` 增加 `short-url.click.async-enabled: true` 与压测专用 `short-url.click.simulate-db-latency-ms: 0`。该开关同时是**生产降级开关**（MQ 不可用时退化为同步，不丢统计）。完整数据见 [docs/QPS压测报告.md](file:///c:/Users/LENOVO/Desktop/short-url/docs/QPS压测报告.md)。

**压测工具**：原 `tools/load_test.py`（线程版）实测被 Python GIL 锁在 ~3.3k QPS，会掩盖后端差异，**不可用**。新增 `tools/load_test_async.py`（asyncio + HTTP/1.1 keep-alive，纯标准库）突破 GIL，才能把后端压到饱和。

**关键结论（反直觉但真实）**：
1. **平凡单行写（本机低延迟）下 MQ 并不更快**：SYNC ~6.2k QPS / p99 144ms，MQ ~3.7~4.7k QPS / p99 305~630ms。原因：broker 发布一跳（JSON+网络+流控）比连接池内单行 `UPDATE` 更贵；且单消费者逐条写追不上生产者 → 队列堆积 → broker 内存高水位流控阻塞 publisher。
2. **MQ 的真正收益是"解耦请求与下游延迟"（韧性）**：模拟下游 30ms 时，SYNC 模式 min=31.5ms（100% 请求被钉死在慢下游上），MQ 模式 min=0.24ms（30ms 已移到消费者线程，请求路径外）。DB 抖动/降级时 MQ 模式 302 照常飞快，点击统计仅最终一致、延迟，绝不阻塞用户。
3. **吞吐收益需靠消费者演进兑现**：批量聚合 +1（`UPDATE ... +N`）、消费者并发、`publisher-confirm` 按需关闭——阶段2 设计文档已预留"批量聚合"演进点。

**复现**：起后端 `-Dshort-url.click.async-enabled=false|true`；压测 `python tools/load_test_async.py -u http://localhost:8082/<短码> -n 100000 -c 500`；韧性演示加 `-Dshort-url.click.simulate-db-latency-ms=30`。

### 12.12 独立 Maven 在 Git Bash 下的调用方式（踩坑记录）

IDEA 自带的 Maven（`JetBrains/.../plugins/maven/lib/maven3/bin/mvn`）在 Git Bash 下因 classworlds 不在 classpath 而失败。已下载独立 Maven 3.9.9 到 `C:\Users\LENOVO\.tools\maven\apache-maven-3.9.9`。Git Bash 下必须走 `java` 直接拉起 launcher（路径用 `cygpath -w` 转 Windows 格式），不能直接调 `mvn` 脚本：
```bash
M="/c/Users/LENOVO/.tools/maven/apache-maven-3.9.9"
CW_WIN=$(cygpath -w "$M/boot/plexus-classworlds-2.8.0.jar")
M_WIN=$(cygpath -w "$M")
PROJ_WIN=$(cygpath -w "/c/Users/LENOVO/Desktop/short-url/short-url-server")
JAVA="/c/Users/LENOVO/Desktop/jdk-17.0.18+8/bin/java"
cd "/c/Users/LENOVO/Desktop/short-url/short-url-server"
"$JAVA" -cp "$CW_WIN" -Dclassworlds.conf="$M_WIN/bin/m2.conf" -Dmaven.home="$M_WIN" -Dmaven.multiModuleProjectDirectory="$PROJ_WIN" org.codehaus.plexus.classworlds.launcher.Launcher compile -B
```
> 注：在 IDEA 内直接点运行/Debug 不受此限制，此方式仅用于无 IDE 的命令行编译验证。

---

## 十三、给新会话的恢复提示词

将以下文字复制到新对话开头即可恢复上下文：

```
我在做一个短链管理系统，项目位于 C:\Users\LENOVO\Desktop\short-url\，完整历史档案在根目录的 PROJECT_HISTORY.md 文件里（含技术选型、踩坑、环境安装位置），请先读一下这个文件了解上下文。

【已完成】
- 阶段1 MVP（SpringBoot 3.3.5 + Redis + MySQL 8 + Vue3 + Vite4 + ElementPlus），三个核心接口(创建/列表/跳转)跑通。
- 阶段2 RabbitMQ 异步点击统计：跳转请求里"查URL"留在线(硬依赖)，"点击+1"丢给 MQ 异步消费(副作用)。新增 mq/ClickMessage.java、config/RabbitMQConfig.java(Direct+死信DLX+JSON转换器)、mq/ClickProducer.java(发消息,异常只记日志不阻塞跳转)、mq/ClickCountConsumer.java(手动ACK,失败进死信)；pom 加 spring-boot-starter-amqp；ShortUrlServiceImpl.getOriginalUrl() 两处同步 incrementClickCount 改为发 MQ，recordClick() 供消费者异步 click_count+1。文档：docs/阶段2版本说明书.md。
- RabbitMQ 已装好并实测：D:\rabbitmq\，Windows 服务开机自启(Automatic, 验证 Running)，localhost:5672/guest/guest。
- Redis 已装 D:\Redis-x64-3.2.100，并注册成 Windows 服务(开机自启)，但当前是用户手动起的实例在跑。

【关键决策】
- 不拆分微服务：当前规模(3接口/单机/单人)远未到临界点，拆分是过度设计；保留单体，只做"读/写副作用"解耦(读留在线、写丢给MQ)。
- 阶段编号：阶段1=MVP，阶段2=RabbitMQ，阶段3=SpringAI，阶段4=缓存/并发进阶(布隆/多级缓存/号段)。微服务不占编号。

【已完成（本轮）】
- QPS 压测已跑通：用"办法 B 开关"实现 A/B（`short-url.click.async-enabled` + 压测用 `simulate-db-latency-ms`），新增 asyncio 压测工具 `tools/load_test_async.py`（线程版被 GIL 锁死不可用）。结论：本机平凡写 MQ 略慢于同步(~0.6~0.75x)，但 MQ 的真正收益是"解耦请求与慢下游延迟"（韧性）。详见 docs/QPS压测报告.md 与 12.11。

【用户偏好/注意】
- 中文沟通；能自动执行就直接做，不要让用户手动操作；但绝不要擅自结束用户正在手动运行的进程/服务实例(曾因 auto-kill 手动Redis被提醒)。
- 前端启动是 `npm run dev`(Vite项目,不是老Vue CLI的 `npm run serve`)。
- 编译后端若用命令行 Maven：Git Bash 下 IDEA 自带 mvn 路径有问题，用独立 Maven C:\Users\LENOVO\.tools\maven\apache-maven-3.9.9，且必须用 java 直接拉起 launcher(见 PROJECT_HISTORY.md 12.12)，不能直接调 mvn 脚本。

请按 PROJECT_HISTORY.md 的内容继续协助我，优先把 QPS 压测跑起来并用数据证明 MQ 的收益。
```

---

## 十四、概念结论定稿与简历口径（2026-08-29 会话，从 .workbuddy 记忆归档）

> 以下为与用户已达成共识的概念结论，**直接采信，勿再争论**。原存于 `.workbuddy/memory/`（已删除，内容并入本节）。

### 14.1 简历/对外表述口径（重要，避免前后不一致）

- 本项目 MQ（阶段2）收益**不能写"QPS/性能提升"**：实测本机平凡单行写下，MQ 反而 ~0.6~0.75x 同步版 QPS（多了 broker 发布一跳 + 单消费者追不上生产者导致背压）。
- **正确写法**：强调"读在线/写异步"解耦、跳转与点击统计解耦、下游抖动不影响 302 跳转（实测慢下游 30ms 时请求 min 延迟 31.5ms→0.24ms）、削峰、最终一致、死信队列+手动ACK 可靠投递、MQ 不可用时同步降级开关。
- 若要在简历写"吞吐反超同步"，需先做"批量聚合+1 / 消费并发"优化并复测拿到真数，否则别写。

### 14.2 性能/并发概念结论（口径定稿）

1. QPS 是吞吐指标，最大QPS=吞吐上限；衡量要配延迟 SLO。
2. 最大QPS = 并行度 ÷ 单请求服务时间（Little's Law 形式）。并行度 = 同一时刻真正干活的请求数 = min(各资源池上限)，本项目受 DB 连接池(10) 约束，非 Tomcat 线程池(200)。
3. 本项目是 IO 密集型，服务时间≈最慢 I/O 跳（DB 写 ~1.0ms），非 CPU 密集型；算法优化几乎无效，要减 IO 次数或谨慎调池（调池必须压测验证）。
4. MQ 不提速、实测 0.6x：多发一跳 + 单消费者追不上 → 背压。价值=解耦/异步/削峰/可靠(手动ACK+死信)；代价=性能 + 强一致变最终一致。
5. 解耦收益用 min 延迟证明最干净：同步+慢下游30ms→min=31.5ms（人人等）；MQ+30ms→min=0.24ms（下游离场到消费线程）。avg/p99 会被 MQ 背压污染，不适合证明解耦。
6. 延迟口径：min=最快一次(下界)；p50=中位数；avg=均值(被尾巴拉高)；p99=99分位(99%更快，非最慢；最慢是 max)。
7. 连接池=闸门(并发上限，满了排队/拒绝)，MQ队列=蓄水池(缓冲削峰)，别混。排队等待时间进 p99、不进服务时间分母。

### 14.3 压测环境补充（12.11 的环境注记）

- 压测当晚本机 Redis 未运行，用户手动拉起（`D:\Redis-x64-3.2.100`，net start 需管理员被拒）。
- 未触碰 8081 上原有后端（PID 14780）；压测后端跑在 8082。

