# SWPay 项目面试准备（4年 Java 方向）

> 目标：你可以基于这个项目，完成 **3分钟项目介绍 + 10分钟深挖问答**，并且把回答落在“业务价值 + 技术实现 + 个人贡献”上。

---

## 1. 项目速览（先背这页）

### 1.1 项目定位
- 项目：`swpay`（Maven `war` 项目），定位为支付结算平台前台/开放能力聚合系统。
- 主要业务：支付订单、代付、提现、退款、保证金、风控、对账/财务审核、渠道接入。
- 代码规模（可用于面试开场量化）：
  - `src/main/java` 约 `2100` 个 Java 文件
  - `src/test/java` 约 `127` 个测试文件
  - `src/main/webapp` 约 `179` 个 JSP 页面

### 1.2 技术栈（面试官最爱问）
- Java 8 + Maven + Tomcat（`servlet 2.5` 时代架构）
- Spring 3.2 + Struts2 + iBatis2 + CXF JAX-RS
- Redis（Sentinel）、ActiveMQ、Quartz
- Druid 连接池、主从数据源动态路由、AOP 切面

### 1.3 关键“证据文件”
- Web入口与过滤器链：`src/main/webapp/WEB-INF/web.xml`
- Spring总装配：`src/main/resources/spring.xml`
- Struts动作总路由：`src/main/resources/struts.xml`
- REST + Gateway 装配：`src/main/resources/spring/gateway-spring.xml`
- 数据源/事务/主从：`src/main/resources/com/shunwang/swpay/beans-datasource-swpay.xml`
- iBatis 映射总表：`src/main/resources/com/shunwang/swpay/SqlMapConfig-swpay.xml`

---

## 2. 面试时可直接讲的“架构主线”

### 2.1 三类入口并存（这是项目复杂度亮点）
1. 页面入口（Struts）
   - `/*.htm`，大量 Action + JSP 页面流
2. 统一网关入口（Servlet）
   - `/gateway.do`
   - 通过 `service` 参数动态分发到内部地址（数据库配置可热更新）
3. REST 入口（CXF）
   - `/service/*`
   - JAX-RS 风格开放接口（订单、代付、余额、审核等）

### 2.2 横切能力（可体现架构思维）
- 安全链路：签名校验、IP白名单、时间窗校验、参数校验、XSS过滤
- 风控链路：`@RiskManage` 注解切面自动上报并可拦截交易
- 数据链路：主从路由 + 事务 + SQL 参数加解密切面
- 可靠性链路：MQ异步处理 + Quartz 定时补偿/对账/状态修复

---

## 3. 你要重点准备的 6 个深挖话题

## 3.1 统一网关动态路由
- 关键点：`GatewayServlet` 读取 `service`，经 `GatewayFactory` 映射到内部处理路径。
- 亮点：
  - 接口状态开关（关闭后直接拒绝）
  - 请求方法限制（GET/POST 位运算校验）
  - Quartz 定时刷新网关配置，支持动态变更
- 代码位置：
  - `src/main/java/com/shunwang/swpay/gateway/servlet/GatewayServlet.java`
  - `src/main/java/com/shunwang/swpay/gateway/factory/GatewayFactory.java`

## 3.2 开放接口安全模型（分层防护）
- 典型链路：
  - 网关拦截器 / REST 请求处理器
  - 校验 `partner`、签名、IP白名单、时间参数
  - 注解驱动参数校验 `@ParamValid`
  - Web 层 XSS Filter
- 代码位置：
  - `src/main/java/com/shunwang/swpay/common/interceptor/GatewayInterceptor.java`
  - `src/main/java/com/shunwang/swpay/rest/auth/AbstractAuthorizationRequestHandler.java`
  - `src/main/java/com/shunwang/swpay/gateway/valid/ValidatorUtils.java`
  - `src/main/java/com/shunwang/swpay/common/filter/XSSFilter.java`

## 3.3 读写分离与事务控制
- 关键点：
  - `DynamicDataSource` + `ThreadLocal` 路由主从
  - `DataSourceAspect` 按方法名/事务策略选择库（查询走从库）
  - 支持 `@TargetDataSource` 手工指定路由
  - Spring 事务规则统一配置（`query*/find*/get*` 只读）
- 代码位置：
  - `src/main/resources/com/shunwang/swpay/beans-datasource-swpay.xml`
  - `src/main/java/com/shunwang/sqlClientAop/DataSourceAspect.java`
  - `src/main/java/com/shunwang/sqlClientAop/DynamicDataSource.java`
  - `src/main/java/com/shunwang/sqlClientAop/TargetDataSource.java`

## 3.4 交易一致性与并发控制
- 典型做法：
  - 订单关闭/修改流程里先加锁查单（`with lock` 思路）
  - 校验状态机（仅允许特定状态流转）
  - 事务中执行账户解冻与订单更新
- 代码位置：
  - `src/main/java/com/shunwang/swpay/disburse/bo/imp/OrderServiceBoImpl.java`
  - `src/main/java/com/shunwang/swpay/disburse/dao/DisburseListDao.java`

## 3.5 异步化与补偿机制
- 关键点：
  - ActiveMQ 处理异步支付/通知/超时等队列
  - Quartz 大量任务做渠道对账、结果轮询、状态修复
  - 典型“同步核心链路 + 异步后置处理”
- 代码位置：
  - `src/main/resources/com/shunwang/jms/jms-spring.xml`
  - `src/main/java/com/shunwang/jms/disburse/DisburseProcessJms.java`
  - `src/main/resources/spring/timetask-spring.xml`

## 3.6 风控切面化
- 关键点：
  - 业务方法打 `@RiskManage` 注解即可接入风控
  - 切面统一提取字段、调用风控接口、按结果分级处理（拦截/提示）
  - 不污染核心业务代码
- 代码位置：
  - `src/main/java/com/shunwang/swpay/common/interceptor/RiskManageAspect.java`
  - `src/main/java/com/shunwang/swpay/rest/service/impl/OrderServiceImpl.java`

---

## 4. 面试可直接复述的项目介绍模板

## 4.1 3分钟版本（建议背熟）
我参与的是一个支付结算平台项目，核心是同时支持页面端、统一网关和 REST 开放接口三种接入模式。系统使用 Spring + Struts2 + iBatis + CXF，主要承载订单、代付、提现、退款、风控和财务审核等能力。

在架构上，我们把通用能力做成了横切：安全校验（签名/IP/时间窗/参数校验）、风控切面、读写分离、异步消息和定时补偿，保障交易链路在高并发和多渠道场景下的稳定性。比如统一网关通过数据库路由配置 + 定时刷新，支持动态开关接口；订单关键流程通过加锁查询和事务控制保证状态流转一致性；渠道回调和结果轮询则通过 MQ + Quartz 做解耦和补偿。

我的工作重点在【这里替换成你的真实内容：例如网关路由、订单状态流转、风控接入、某渠道对接、性能优化】。最终收益可以从【成功率、响应时间、故障恢复时长、人工处理量】几个指标体现。

## 4.2 10分钟深挖结构
- 业务背景：为什么需要三种入口并存（历史兼容 + 开放平台化）
- 技术方案：统一网关 + REST + Struts 页面层
- 关键难点：一致性、安全、异步可靠性
- 你的贡献：至少 2 个“有输入-有动作-有结果”的案例
- 复盘优化：你如何推动治理（安全、可观测性、技术升级）

---

## 5. 高频面试问题（项目定制版）

## 5.1 架构类
1. **为什么这个项目不用纯 Spring MVC / Spring Boot？**
   - 答：历史包袱+存量系统稳定性要求高，采用渐进式演进；当前通过多入口兼容保障业务连续。

2. **统一网关设计的价值是什么？**
   - 答：把外部入口收敛为统一鉴权/路由/开关层，减少渠道接入成本，提升治理能力。

3. **读写分离怎么落地？**
   - 答：`DynamicDataSource + AOP + ThreadLocal`，按事务/方法名前缀路由到主从库。

## 5.2 事务与一致性
4. **订单关闭如何保证一致性？**
   - 答：锁单查询 + 状态机校验 + 事务内解冻与更新 + 幂等判断（已终态直接返回）。

5. **同步与异步如何划分？**
   - 答：资金与状态变更等核心动作走同步事务；通知、轮询、补偿走 MQ/定时任务。

6. **如果消息重复消费怎么办？**
   - 答：结合业务主键/订单状态幂等判断，确保重复处理不破坏状态。

## 5.3 安全与风控
7. **接口防刷/防伪造怎么做？**
   - 答：签名 + IP白名单 + 时间窗 + 参数合法性校验 + 限流（可补充你真实实践）。

8. **风控如何做到低侵入？**
   - 答：注解 + AOP，统一抽取业务字段并调用风控服务，按风险等级拦截或提示。

## 5.4 性能与稳定性
9. **高峰期慢查询/连接池问题怎么排查？**
   - 答：先看慢SQL和连接池参数，再看主从路由是否命中，最后看热点接口与缓存策略。

10. **线上故障如何快速止损？**
   - 答：利用网关/接口状态开关快速熔断某渠道；异步补偿任务兜底修复状态。

---

## 6. 你必须准备的“个人贡献证据”（别空讲）

> 面试官会追问“你到底做了什么”。建议按 STAR 写 2~3 个案例。

模板（请替换成你的真实数据）：

### 案例A：统一网关治理
- S：多渠道接口分散，变更和管控成本高。
- T：统一路由并支持动态开关。
- A：落地 `service` 动态分发、方法限制、状态控制、定时刷新配置。
- R：发布效率提升 X%，故障止损时间从 X 分钟降到 X 分钟。

### 案例B：订单一致性优化
- S：订单在并发和渠道超时下易出现状态不一致。
- T：保证关键状态流转可回放、可补偿。
- A：锁单+事务+状态机校验，补充异步补偿任务。
- R：异常订单率下降 X%，人工对账处理量下降 X%。

### 案例C：风控接入改造
- S：风控逻辑散落在业务代码中，可维护性差。
- T：统一接入并减少业务侵入。
- A：用注解+AOP抽取字段并上报风控，按级别拦截/提示。
- R：风控策略迭代效率提升 X%，误拦截率下降 X%。

---

## 7. 面试加分：你可以主动提的“改造计划”

## 7.1 短期可落地
- 把敏感配置从本地文件迁移到密钥管理（避免硬编码/明文风险）。
- 补齐测试执行链路（当前 `pom.xml` 默认跳过 surefire 测试）。
- 补充关键链路可观测性：traceId、业务日志标准化、报警分级。

## 7.2 中期演进
- 逐步把 Struts Action 迁移为 Spring Boot REST 服务。
- 渠道能力做插件化/适配器抽象，减少分支逻辑。
- 引入更标准的异步可靠模式（如 Outbox、幂等中间层）。

## 7.3 长期目标
- 按“交易域、账户域、清结算域”拆分服务边界。
- 建立统一接口网关与策略中心（鉴权、限流、灰度、审计）。

---

## 8. 7天冲刺复习建议（结合这个项目）

- Day1：背熟项目主线（入口、链路、业务边界）
- Day2：复盘网关与安全（签名/IP/时间窗/参数校验）
- Day3：复盘事务与一致性（锁单、状态机、补偿）
- Day4：复盘 MQ 与定时任务（异步、轮询、修复）
- Day5：准备 3 个 STAR 案例（必须有数据）
- Day6：模拟面试（技术深挖 + 场景题）
- Day7：查漏补缺（简历项目描述对齐口述）

---

## 9. 最后提醒（非常关键）

- 不要编造指标，所有结果都要可解释、可追溯。
- 所有“我做了”都尽量落到代码点、方案点、结果点。
- 4年经验面试重点不是“会用框架”，而是“能解决复杂交易场景下的稳定性与一致性问题”。

