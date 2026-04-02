# 项目面试手册

## 1. 项目定位

这个项目不是单一活动页，而是一个带有中台属性的营销平台。

它将多种营销能力统一到一个系统中，包括：

- 权益发放
- 问卷活动
- 任务中心
- 积分商城
- 支付与退款回调
- AI 对话与 AI 游戏
- 多渠道页面配置
- 对接 SSO、微信、支付、腾讯、大数据、AI 等外部系统

面试时可以把项目定位为：

`一个基于 Spring Boot 的营销活动平台，支持多渠道、多活动、多奖励形态的业务场景。`

## 2. 架构概览

项目采用典型的多模块分层结构：

- `marketing-web`：控制层、接口层、页面入口
- `marketing-service`：业务层、规则编排、外部系统集成
- `marketing-dao`：数据访问层，基于 MyBatis

核心技术栈：

- Spring Boot 2.7
- MyBatis
- Redis
- Thymeleaf
- MySQL
- Druid
- Ehcache
- OkHttp

工程化特征：

- 自定义动态数据源
- 事务管理
- 缓存支持
- 定时任务支持
- 过滤器与拦截器治理请求
- Redis 防重和轻量幂等
- 外部系统统一 Client 封装

## 3. 项目主要功能

### 3.1 权益中心

主要能力：

- 展示权益首页和启动页
- 根据登录状态和环境展示不同权益
- 领取 CDKEY、红包、虚拟权益等
- 防止重复点击和频繁提交

可参考代码：

- `marketing-web/src/main/java/com/shunwang/marketing/controller/WelfareController.java`
- `marketing-service/src/main/java/com/shunwang/marketing/service/marketing/impl/WelfareServiceImpl.java`

面试表达：

`我参与了权益领取主链路的开发，包括权益展示、登录态处理、领取资格校验、Redis 防重、库存扣减和 CDKEY 发放。`

### 3.2 问卷活动

主要能力：

- 查询问卷基础信息
- 查询问卷详情和题目
- 判断用户是否已填写
- 提交问卷答案
- 完成问卷后领取奖励
- 上传问卷图片

可参考代码：

- `marketing-web/src/main/java/com/shunwang/marketing/controller/SurveyController.java`
- `marketing-service/src/main/java/com/shunwang/marketing/service/marketing/impl/SurveyServiceImpl.java`

面试表达：

`我负责过问卷活动的完整业务链路，包括问卷状态校验、重复提交校验、答案保存和奖励发放。`

### 3.3 AI 游戏与 AI 互动

主要能力：

- 创建 AI 会话
- 查询对话历史
- 用户发起聊天
- 判断游戏是否完成
- 完成后发放奖励

可参考代码：

- `marketing-web/src/main/java/com/shunwang/marketing/controller/AiGameController.java`
- `marketing-service/src/main/java/com/shunwang/marketing/service/marketing/impl/AiGameServiceImpl.java`
- `marketing-service/src/main/java/com/shunwang/marketing/manager/ai/AiServiceClient.java`

面试表达：

`我参与了 AI 能力和营销玩法的结合，把 AI 会话、对话记录、提示词配置和发奖链路串成一套可运营的活动玩法。`

### 3.4 任务中心

主要能力：

- 按渠道加载在线任务
- 区分积分任务和权益任务
- 完成转发任务
- 完成助力任务

可参考代码：

- `marketing-web/src/main/java/com/shunwang/marketing/controller/TaskCenterController.java`

面试表达：

`任务中心主要用于提升活跃和转化，我重点参与了任务完成、助力关系处理、防重复提交和奖励发放相关逻辑。`

### 3.5 页面、Tab、模块配置化

主要能力：

- 页面配置查询
- 标签页配置查询
- 模块按结构动态加载
- 支持不同平台和不同渠道
- 支持小程序和助手页面

可参考代码：

- `marketing-web/src/main/java/com/shunwang/marketing/controller/PageController.java`
- `marketing-web/src/main/java/com/shunwang/marketing/controller/TabController.java`
- `marketing-web/src/main/java/com/shunwang/marketing/controller/ModuleController.java`
- `marketing-service/src/main/java/com/shunwang/marketing/service/marketing/impl/PageServiceImpl.java`

面试表达：

`这个项目比较有价值的一点是配置化。页面、Tab、模块和奖励不是写死在代码里的，而是通过配置组合出来，支持快速上线不同活动。`

### 3.6 积分商城与商品兑换

主要能力：

- 查询积分账户
- 查询积分明细
- 商品兑换
- 关闭订单
- 查询订单奖励

可参考代码：

- `marketing-web/src/main/java/com/shunwang/marketing/controller/ScoreController.java`

面试表达：

`我参与了积分兑换链路，重点处理了兑换并发控制、订单状态流转和奖励结果查询。`

### 3.7 支付与退款回调

主要能力：

- 处理多种订单回调
- 处理支付完成回调
- 处理退款回调
- 避免重复回调处理
- 成功后触发异步通知

可参考代码：

- `marketing-web/src/main/java/com/shunwang/marketing/controller/api/SwpayController.java`

面试表达：

`我处理过支付回调和退款回调相关逻辑，重点关注幂等、订单状态推进和外部支付系统的稳定性。`

## 4. 架构亮点

### 4.1 多模块分层设计

项目将入口层、业务层、数据访问层拆分开，优点是：

- 降低耦合
- 便于多人协作
- 便于后续扩展和维护

### 4.2 配置驱动的营销能力

这是这个项目最值得讲的点之一。

项目中有比较清晰的抽象：

- page
- tab
- module
- module detail
- banner
- channel

说明它更接近营销中台，而不是简单 CRUD 系统。

### 4.3 Redis 防重与轻量幂等

项目中大量使用 Redis `setIfAbsent` 实现短时间防重复提交。

典型场景：

- 领取权益
- 提交问卷
- AI 聊天
- 完成助力任务
- 支付回调

可参考代码：

- `marketing-service/src/main/java/com/shunwang/marketing/cacheService/RedisOperation.java`

### 4.4 外部接口统一封装

Controller 没有直接散落大量 HTTP 调用，而是通过 Request/Response/Client 统一封装。

可参考代码：

- `marketing-service/src/main/java/com/shunwang/marketing/manager/service/SsoServiceClient.java`
- `marketing-service/src/main/java/com/shunwang/marketing/manager/ai/AiServiceClient.java`

好处：

- 便于复用
- 协议隔离更清晰
- 后续替换成本更低
- 更容易做测试和治理

### 4.5 过滤器与拦截器治理请求

项目通过过滤器和拦截器处理：

- Trace
- Session
- 登录校验
- 签名校验
- ThreadLocal 上下文注入
- XSS 防护

可参考代码：

- `marketing-web/src/main/java/com/shunwang/marketing/filter/WebFilterConfig.java`
- `marketing-web/src/main/java/com/shunwang/marketing/interceptor/WebConfig.java`

### 4.6 设计模式的使用

活动弹窗策略使用了责任链模式。

可参考代码：

- `marketing-service/src/main/java/com/shunwang/marketing/service/marketing/promotionPopupWindow/WindowChain.java`

这个点很适合在面试里证明你做的不是纯过程式业务代码。

## 5. 面试时怎么介绍项目

### 5.1 一分钟版本

`这是一个基于 Spring Boot、MyBatis、Redis 和 Thymeleaf 的营销平台，主要支持权益发放、问卷活动、任务激励、积分兑换、支付回调和 AI 互动等业务。系统本身支持多渠道、多页面、多奖励形态，很多活动能力是配置驱动的。我主要参与的是核心业务接口、幂等控制、奖励发放和外部系统对接稳定性相关的工作。`

### 5.2 三分钟版本

`这个项目本质上不是一个单点活动系统，而是一个营销平台。它把页面、Tab、模块、权益、任务、积分、支付和 AI 等能力统一起来，通过配置组合支持不同渠道和不同活动快速上线。后端主要基于 Spring Boot、MyBatis、Redis 和 MySQL，同时对接了 SSO、支付、微信、腾讯、大数据和 AI 服务。`

`我参与的重点主要在几个核心链路上，比如权益领取、问卷奖励、积分兑换、AI 游戏互动和支付回调。在这些场景里，我关注的不只是功能实现，还包括防重复提交、Redis 幂等、库存一致性、事务控制和外部依赖稳定性。`

`如果总结这个项目的核心难点，我认为不是某个单独接口，而是在多活动、多渠道、多奖励类型并行的情况下，如何把系统做得可扩展、稳定且可维护。`

## 6. 高频面试问题与回答思路

### 6.1 这个项目最难的地方是什么

回答思路：

`最难的不是单个接口，而是业务复杂度。因为这个项目支持多活动、多渠道、多种奖励类型和多个外部系统，所以很多链路都要考虑共性能力，比如幂等、库存一致性、状态流转和失败重试。`

### 6.2 你们是怎么处理高并发的

回答思路：

`我们在高频接口里大量使用了 Redis setIfAbsent 做短时间防重，比如领取、提交和支付回调。同时我也认为 Redis 只是第一层保护，更重要的是业务状态校验和数据库层面的幂等设计。`

### 6.3 这个项目为什么说具备可扩展性

回答思路：

`一方面它有比较清晰的配置模型，比如 page、tab、module、reward，不同活动可以复用这些基础能力。另一方面外部系统也做了统一封装，所以新增活动或新增渠道时，不需要推翻原有架构。`

### 6.4 你觉得项目还有哪些不足

回答思路：

`项目已经有一定平台化能力，但有些核心流程还是通过 if else 和前缀判断来分发业务，后续更适合演进成策略模式和工厂模式。另外幂等控制目前偏轻量，应该进一步加强数据库唯一约束和状态机设计。`

## 7. 从架构角度可以改进的地方

这部分是面试最加分的内容。

### 7.1 提升业务扩展性

现状问题：

- 奖励发放逻辑中有较多 `if else`
- 支付回调通过订单前缀分流
- 随着业务增长，分支会越来越重

建议方案：

- 引入 `策略模式 + 工厂模式`
- 按奖励类型拆分发奖处理器
- 按订单类型拆分回调处理器
- 按活动玩法拆分业务策略

收益：

- 新增业务类型时改动更小
- 代码更符合开闭原则
- 降低回归风险

### 7.2 强化真正的幂等能力

现状问题：

- 很多接口主要依赖 Redis 短锁
- Redis 短锁能防重，但不能完全解决业务一致性

建议方案：

- 对一人一奖、一单一回调增加数据库唯一约束
- 建立订单状态机和发奖状态机
- 对异步通知使用消息表或 outbox 模式

收益：

- 重试和重复回调下更稳定
- 部分失败后更容易恢复

### 7.3 降低 N+1 查询风险

现状问题：

- 某些权益状态判断是按权益逐个查询
- 权益数一多，数据库压力会放大

建议方案：

- 批量查询用户已领取记录
- 转成内存 Set
- 一次遍历回填状态

收益：

- 减少数据库往返次数
- 提升列表接口响应速度

### 7.4 完善外部依赖治理

现状问题：

- 外部系统虽然做了统一封装
- 但超时、重试、熔断、降级还不够体系化

建议方案：

- 引入 `Resilience4j`
- 统一管理超时配置
- 只对安全幂等接口做重试
- 对慢依赖做隔离
- 非核心依赖支持降级返回

收益：

- 避免外部系统抖动拖垮主链路
- 增强系统韧性

### 7.5 提升安全治理能力

现状问题：

- 配置文件中出现数据库、Redis、邮件、微信等敏感信息

建议方案：

- 敏感配置迁移到配置中心或密钥管理系统
- 按环境隔离配置
- 对关键参数做脱敏和权限控制

收益：

- 降低运维和泄露风险
- 更符合生产安全规范

### 7.6 加强配置治理

现状问题：

- 允许循环依赖
- 本地测试配置出现在默认配置中
- 某些依赖版本控制不够严格

建议方案：

- 逐步消除 Service 循环依赖
- 本地配置与公共配置分离
- 固定依赖版本，避免使用不稳定版本策略

收益：

- 部署更安全
- 构建结果更可预测
- 依赖关系更清晰

### 7.7 完善可观测性

现状问题：

- 已有日志和邮件告警
- 但整体可观测性还不够系统化

建议方案：

- 增加接口 QPS、RT、错误率监控
- 监控支付回调成功率、发奖成功率、外部调用成功率
- 关键业务链路打通 traceId
- 对库存扣减、领奖、回调处理增加审计日志

收益：

- 更容易定位线上问题
- 更容易发现性能瓶颈

### 7.8 补齐测试体系

建议方案：

- Service 层单元测试
- 发奖与回调链路集成测试
- 并发压测和幂等测试
- 第三方接口契约测试

收益：

- 降低回归风险
- 提升版本发布质量

## 8. 你可以怎么描述自己的贡献

不要只说：

`我写了几个 Controller 和接口`

更好的表达方式：

- 我参与了权益领取、问卷奖励、积分兑换、AI 游戏和支付回调等核心营销链路的开发
- 我在高频场景下用 Redis 做了防重和轻量幂等控制
- 我参与了外部系统对接和稳定性处理
- 我重点关注奖励发放、订单回调、库存控制等一致性问题
- 我做的不只是功能开发，也在推动系统从单活动实现向可复用营销平台演进

## 9. 面试收尾总结

最后可以这样总结：

`这个项目让我积累的不只是 CRUD 经验，更重要的是让我理解了在多渠道、多活动、多奖励类型场景下，如何从架构层面思考可扩展性、幂等性、一致性和外部依赖治理。对我来说，这个项目最大的价值是让我开始站在平台化和系统设计的角度做业务。`
