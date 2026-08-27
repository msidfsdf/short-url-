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
阶段2（待做）  : + SpringCloud                       → 微服务化拆分
阶段3（待做）  : + RabbitMQ                          → 异步统计、削峰
阶段4（待做）  : + SpringAI                          → 智能生成标题/数据分析
```

---

## 二、核心技术实现思路

### 2.1 五个核心问题

| 问题 | 方案 |
|---|---|
| 短链码生成 | **自增ID + 62进制转换**（6位短码支持568亿链接） |
| 短链跳转 | HTTP **302** 临时重定向（能统计点击量，不缓存） |
| 缓存设计 | Cache Aside 模式，Redis key: `short:url:{code}` |
| 点击统计 | 阶段1简单 UPDATE，阶段3用 MQ 异步削峰 |
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

### 阶段2: 引入 SpringCloud（微服务化）
- 拆分：`short-url-gateway`（网关） + `short-url-service`（核心） + `short-url-stat-service`（统计）
- 用 Nacos 做注册中心与配置中心
- 用 OpenFeign 做服务间调用

### 阶段3: 引入 RabbitMQ（异步削峰）
- 跳转点击事件 → 发到 MQ → 消费者批量 UPDATE（解决高并发统计打DB问题）
- 死信队列处理失败消息

### 阶段4: 引入 SpringAI（智能化）
- AI 自动生成短链标题/描述
- AI 分析点击趋势生成报表
- AI 智能推荐热门短链

### 阶段5（可选进阶）: 解决面试高频问题
- **布隆过滤器** 防止缓存穿透
- **互斥锁** 解决缓存击穿
- **TTL加随机值** 防止缓存雪崩
- **号段模式** 替代自增ID，提升并发能力
- **本地缓存 (Caffeine)** 多级缓存架构

---

## 十、用户偏好与环境备忘

- **沟通语言**: 中文
- **操作系统**: Windows (PowerShell 环境)
- **IDE**: IntelliJ IDEA 2025.1
- **MySQL 密码**: root
- **项目根目录**: `C:\Users\LENOVO\Desktop\short-url\`
- **用户意图**: 找开发实习，需要技术深度项目
- **用户偏好**: 能自动执行的操作就直接执行，不要让用户手动操作
- **用户熟悉技术栈**: Spring Cloud、Spring Boot、Nacos、MyBatis-Plus、Maven、RabbitMQ、Feign、Redis

---

## 十一、给新会话的快速恢复指南

如果你在新会话里要继续这个项目，只需告诉 AI：

> 我在做一个短链管理系统，项目位于 `C:\Users\LENOVO\Desktop\short-url\`，已完成阶段1 MVP（SpringBoot+Redis+MySQL+Vue3），现在要做阶段X...

然后根据需要选择：
- 做 **阶段2 SpringCloud**：让 AI 帮你拆分微服务、引入 Nacos
- 做 **阶段3 RabbitMQ**：让 AI 帮你改造点击统计为异步
- 做 **阶段4 SpringAI**：让 AI 帮你集成 AI 能力
- **优化现有代码**：让 AI 帮你加布隆过滤器/多级缓存等

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
| 点击统计（UPDATE） | **极高**（每次点击） | DB 写压力 | 同步 UPDATE（[ShortUrlServiceImpl.java#incrementClickCount](file:///c:/Users/LENOVO/Desktop/short-url/short-url-server/src/main/java/com/shorturl/service/impl/ShortUrlServiceImpl.java)） | **阶段3 用 RabbitMQ 异步**：消息发到 MQ → 消费者批量 UPDATE，削峰 |

**核心瓶颈**：跳转流程里的"同步 UPDATE 点击数"是当前最大性能瓶颈，每次点击都打一次 DB 写。

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

---

## 十三、给新会话的恢复提示词

将以下文字复制到新对话开头即可恢复上下文：

```
我在做一个短链管理系统，项目位于 C:\Users\LENOVO\Desktop\short-url\，完整历史档案在根目录的 PROJECT_HISTORY.md 文件里，请先读一下这个文件了解上下文。

当前进度：阶段1 MVP 已完成（SpringBoot 3.3.5 + Redis + MySQL 8 + Vue 3），三个核心接口（创建/列表/跳转）跑通。

本次会话我主要做技术复盘，已经讨论清楚：
- 短链跳转用 HTTP 302 临时重定向（不缓存才能统计点击）
- 短码用"自增ID + 62进制"（避免哈希碰撞）
- Redis 用 String 结构，key=short:url:{code}，TTL 7天，Cache Aside 模式
- 当前最大并发瓶颈是跳转时同步 UPDATE 点击数，下阶段用 RabbitMQ 异步化
- 前端 Vue3+Vite+ElementPlus+axios 架构已明确

待办：
1. 完善"阶段1 版本说明书"文档（工作流程部分要写简单一点），然后写入项目
2. 进入阶段3：引入 RabbitMQ 把点击统计改成异步

请按 PROJECT_HISTORY.md 的内容继续协助我。
```

