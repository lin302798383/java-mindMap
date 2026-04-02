# 项目核心业务逻辑面试总览

## 1. 文档目的

这份文档是在上一份 `docs/settle-interview-guide.md` 的基础上，进一步梳理项目中的其他核心业务逻辑，重点覆盖：

- 支付
- 订单查询
- 退款
- 退款查询
- 提现
- 提现查询
- 通知回调
- 撤单 / 冲正
- 签约 / 解约
- Global（海外）支付链路
- 对账、报表、监控等周边能力

目标不是把每个类逐行解释，而是从 **系统分层、业务流程、风险点、面试表达** 四个维度，让你能把整个项目讲清楚。

---

## 2. 我对这个项目的整体判断

从代码结构看，这个项目本质上是一个 **支付任务中心 / 金融异步处理中心**，核心职责不是提供前台支付页面，而是负责：

1. 跟第三方支付/提现渠道交互
2. 定时扫单、查单、补单、补通知
3. 更新内部订单状态
4. 变更用户资金账和商户账
5. 生成通知并回调业务方
6. 执行提现、退款、撤单、签约、对账等资金相关任务

换句话说，这个项目更像：

- **支付状态异步处理中心**
- **资金类补偿与回调中心**
- **渠道接入后的任务编排中心**

它的典型风格是：

- `taskcenter-web`：任务入口、控制器、调度层
- `taskcenter-service`：业务编排、渠道实现、通知、外部接口调用
- `taskcenter-dao`：DAO、枚举、查询对象、POJO、MyBatis SQL

---

## 3. 项目的核心业务域

按业务划分，这个项目至少有以下几个主域：

### 3.1 支付域

负责：

- 订单查单
- 支付成功后的内部账务处理
- 发送支付成功通知
- 部分场景触发分账/代发逻辑

代表代码：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/orderquery/BaseQueryTask.java`
- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/impl/pay/orderquery/AbstractPayOrderService.java`

### 3.2 退款域

负责：

- 发起退款
- 退款状态查询
- 退款成功/失败后的账户处理
- 退款通知

代表代码：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/refund/BaseRefundTask.java`
- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/refundquery/BaseRefundQueryTask.java`
- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/impl/pay/refund/AbstractRefundService.java`

### 3.3 提现域

负责：

- 发起提现
- 提现结果查询
- 解冻、扣款、失败退款
- 提现通知

代表代码：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/outcash/BaseOutCashTask.java`
- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/impl/pay/outcash/AbstractOutCashService.java`

### 3.4 通知域

负责：

- 生成通知单
- 异步投递通知
- 按重试次数扫描补发
- 对回调参数签名
- 记录发送结果和失败重试次数

代表代码：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/notice/NoticeCallbackDispatcherTask.java`
- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/notice/NoticeCallbackTask.java`
- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/impl/business/NoticeMsgServiceImpl.java`

### 3.5 撤单 / 冲正域

负责：

- 对超时未完成订单执行关闭/撤单
- 对已成功支付但业务需要回退的订单生成冲正退款

代表代码：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/pay/ReverseOrderTask.java`
- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/impl/pay/reverse/AbstractReverseService.java`

### 3.6 签约域

负责：

- 支付宝/微信签约状态查询
- 解约状态查询
- 签约成功后通知
- 多渠道签约覆盖/自动解约

代表代码：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/agreement/AgreementQueryTask.java`
- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/agreement/AgreementCancelTask.java`
- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/impl/pay/agreement/AbstractAgreementService.java`

### 3.7 对账域

负责：

- 下载第三方账单
- 解析账单
- 落中间表
- 汇总/明细差异对比

详见：

- `docs/settle-interview-guide.md`

### 3.8 Global（海外）域

负责：

- 海外支付查单
- 海外退款
- 海外通知回调

代表代码：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/global/orderquery/BaseGlobalQueryTask.java`
- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/global/refund/BaseGlobalRefundTask.java`
- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/global/notice/GlobalNoticeCallbackDispatcherTask.java`
- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/global/impl/GlobalNoticeMsgServiceImpl.java`

---

## 4. 项目最重要的统一设计思想

这个项目虽然渠道很多，但底层思路是统一的，主要有 5 个关键词：

### 4.1 Task 驱动

几乎所有核心逻辑都以任务方式运行：

- 定时任务扫描待处理订单
- 单次任务处理一批记录
- 失败后可以靠下一轮任务补偿

这意味着整个系统是明显的 **异步最终一致性模型**。

### 4.2 按渠道注册 / 分发

大量逻辑不是 if/else 写死，而是通过：

- RegisterFactory
- RegisterFactoryManager
- ExposedXXXService

做动态注册和分发。

优点：

- 扩展一个渠道时，只需要补 task/service 实现
- 总控逻辑不需要大改

### 4.3 抽象基类 + 渠道子类

模式非常统一：

- `BaseXxxTask` 负责扫描和遍历
- `AbstractXxxService` 负责公共业务流程
- 具体渠道子类负责外部接口调用和细节差异

这是一种典型的 **模板方法模式**。

### 4.4 事务包裹资金变更

只要涉及：

- 更新订单状态
- 更新用户账户余额
- 更新商户账户余额
- 生成退款单/通知单

基本都使用 `TransactionTemplate` 包住。

说明项目对“状态变更 + 账户变更的一致性”是有意识的。

### 4.5 通知异步化

支付、退款、提现、签约，不是直接同步回调，而是：

1. 先更新业务状态
2. 生成/更新通知单
3. 投递 MQ 或线程池异步执行通知
4. 失败后定时扫描重试

这点非常像成熟支付系统。

---

## 5. 支付链路：项目里是怎么做的

> 这里说的“支付”不是发起收银，而是 **支付结果异步确认 + 订单落账**。

### 5.1 任务入口

核心入口在：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/orderquery/BaseQueryTask.java`

它的逻辑是：

1. 组装查询条件
2. 默认扫最近 10 分钟到 30 秒前创建的订单
3. 只查 `pay_state = 1` 的订单，也就是待确认订单
4. 按 `baId` 找到对应渠道查询服务
5. 循环调用 `payOrderService().orderQuery(payList)`

这里的意思是：

- 新建订单后，不完全依赖同步回调
- 还会靠主动查单把状态补回来

### 5.2 公共支付处理逻辑

公共逻辑在：

- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/impl/pay/orderquery/AbstractPayOrderService.java`

最核心的方法是 `processOrder()`。

支付成功后的标准流程是：

1. 更新充值单状态
2. 给用户资金账户入账
3. 发补贴（如果有补贴）
4. 给商户账入账
5. 记录用户最近一次支付方式
6. 更新通知单
7. 发送通知
8. 如果是代发场景，再进入代发处理

你可以把它理解成：

> 第三方渠道确认成功 -> 内部完成记账 -> 再通知业务系统

### 5.3 为什么这里查单而不是完全依赖回调

因为真实支付系统里常见问题是：

- 第三方支付成功，但回调丢失
- 回调延迟
- 业务接口短暂不可用

所以项目采用了“双保险”模型：

- 有回调就处理回调
- 回调没到就定时查单补状态

### 5.4 支付成功后为什么要更新这么多张表

因为支付成功不是“订单状态改 2”就完了，它会引起：

- 用户余额变化
- 商户账户变化
- 补贴状态变化
- 通知状态变化
- 可能的代发状态变化

这就是资金系统和普通订单系统最大的不同：

> 订单状态变化只是表象，真正复杂的是背后的账务联动。

### 5.5 支付实现的面试说法

可以这样讲：

> 这个项目的支付模块核心不是“发起支付”，而是“支付结果确认和落账”。它通过定时查单任务扫描待确认订单，渠道服务向第三方查询结果后，在事务里更新充值订单、用户资金账、商户账，并生成通知消息。这样既避免单纯依赖回调带来的不确定性，又保证支付成功后内部账务的一致性。

---

## 6. 退款链路：项目里是怎么做的

退款逻辑分两段：

- 发起退款
- 退款查询 / 退款完成

### 6.1 退款任务入口

入口在：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/refund/BaseRefundTask.java`

逻辑是：

1. 根据 remark 或默认条件构造 `RefundOrderQuery`
2. 按退款渠道扫描 `created` 状态的退款单
3. 循环调用对应渠道 `refundService().refund(refundList)`

### 6.2 退款查询任务入口

入口在：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/refundquery/BaseRefundQueryTask.java`

它负责扫描已受理、未最终完成的退款单，再调用：

- `refundService().refundQuery(refundList)`

### 6.3 退款公共处理逻辑

核心在：

- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/impl/pay/refund/AbstractRefundService.java`

公共逻辑可以归纳成两条分支：

#### 分支 A：退款成功 / 冲正退款成功

执行：

1. 变更退款单状态
2. 变更原支付单相关状态
3. 退还/扣减用户账户金额
4. 变更商户账
5. 更新通知单并发送通知

#### 分支 B：退款失败

执行：

1. 解冻相关资金
2. 更新退款单状态为失败
3. 写失败原因
4. 发送通知（如果需要）

### 6.4 为什么退款也要改用户账和商户账

因为退款本质上是支付的逆操作。

支付成功时：

- 用户账增加
- 商户账增加 / 应收变化

退款成功时：

- 用户账减少或回退对应账户项
- 商户账做逆向扣减

代码里还能看到退款会区分：

- 充值资金退款
- 补贴资金退款
- 手续费相关处理

说明这不是简单的一笔金额回退，而是 **按资金来源拆分回退**。

### 6.5 退款模块的难点

这里最关键的难点有三个：

1. **状态机复杂**
   - created
   - accepted
   - success
   - fail
   - reverse_create
   - reverse_success
2. **资金联动复杂**
   - 用户账
   - 商户账
   - 冻结/解冻
3. **渠道差异明显**
   - 每个渠道退款接口都不一样
   - 有的同步成功，有的只返回受理

### 6.6 退款实现的面试说法

> 项目里的退款是典型的异步状态机模型。先由退款任务发起退款，把订单推进到受理态；之后再由退款查询任务持续轮询第三方状态。退款完成后，在事务里处理退款单状态、用户资金账、商户账和通知单。这样可以兼容不同渠道“同步成功”与“异步受理”的差异，同时保证资金口径一致。

---

## 7. 提现链路：项目里是怎么做的

提现链路比支付和退款更像“主动出款”。

### 7.1 任务入口

核心入口在：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/outcash/BaseOutCashTask.java`

它会做这些事：

1. 构造提现扫描条件，默认近 7 天
2. 按渠道扫描待提现订单
3. 对非已审核订单做黑名单校验
4. 某些场景校验存管余额是否足够
5. 调用 `outCashService().outCash(outCashOrder)`

同时也有查询逻辑：

- `outCashService().outCashQuery(outCashOrder)`

### 7.2 提现为什么比支付多了黑名单和余额判断

因为支付是“收钱”，提现是“打钱出去”。

提现天然更敏感，所以代码里额外做了：

- 黑名单校验
- 存管余额校验
- 提现时间窗控制 `canOutCash()`
- 子商户校验 `checkDtMchSub()`

这四点非常像真实资金风控前置。

### 7.3 提现公共处理逻辑

核心在：

- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/impl/pay/outcash/AbstractOutCashService.java`

它的几个关键能力是：

#### 能力 A：提现时间控制

通过系统参数配置禁提时间段，避免通道不可用时仍往外打款。

#### 能力 B：提现成功

调用 `changeOutCashOrderStateAndPayOrder()`：

1. 锁定提现单
2. 校验当前状态是否允许变更
3. 锁定用户账户
4. 解冻/扣款
5. 更新提现单状态

#### 能力 C：提现失败并退款

调用 `changeOutCashOrderStateAndRePayOrder()`：

1. 锁定提现单
2. 解冻原冻结金额
3. 生成一笔退款单
4. 把钱退回用户账户
5. 更新提现单失败状态

#### 能力 D：更新后发消息和通知

提现状态更新后，会：

- 发送 outCashMessage
- 更新通知单
- 触发回调

### 7.4 提现和退款的关系

这套代码里，提现失败很多时候不是简单地把状态改失败，而是：

- 直接生成一笔“退款单”
- 走退款的资金回退逻辑

这说明系统在建模上把“提现失败回退”也纳入退款体系，便于统一处理。

### 7.5 提现实现的面试说法

> 提现链路是主动出款模型。任务先扫描待提现订单，再做黑名单、存管余额、时间窗、子商户等前置校验，之后调用渠道提现接口。提现成功时做解冻和扣款；提现失败时会生成退款单，把冻结资金退回用户，再更新通知。这种设计能把“打款失败回退”也纳入统一资金体系里处理。

---

## 8. 通知链路：项目里是怎么做的

通知模块是这个项目非常关键的一层，因为支付/退款/提现最终都要回调业务方。

### 8.1 通知整体模型

这套代码不是“业务成功就同步调 HTTP 回调”，而是采用：

1. 业务模块更新状态
2. 更新或生成 `NoticeMsg`
3. 异步发送消息 `doSendMessage()`
4. 消费或线程池执行 `doNotice()`
5. 定时扫描失败通知并按重试次数补发

### 8.2 通知数据是怎么准备的

`NoticeMsgServiceImpl` 针对不同业务对象分别提供：

- `updateNoticeMsg(OutCashOrder)`
- `updateNoticeMsg(RefundList)`
- `updateNoticeMsg(PayList)`
- `updateNoticeMsg(AccountAgreement)`

也就是：

- 支付通知数据结构
- 退款通知数据结构
- 提现通知数据结构
- 签约通知数据结构

都是分别构造的。

### 8.3 通知为什么不直接发，要先落通知单

原因有 4 个：

1. 可重试
2. 可审计
3. 可补偿
4. 可观测

否则一旦业务方接口超时，你根本不知道有没有发过、发了几次、回包是什么。

### 8.4 通知发送过程

`doNotice()` 的逻辑可以概括成：

1. 根据 `noticeNo` 查通知单
2. 解析 sendData
3. 如果没有 sign，就现场补签
4. 某些支付回调会补充 riskInfo
5. 发起 HTTP POST
6. 根据返回是否等于 `SUCCESS` 决定通知成功/失败
7. 失败则 `sendNum + 1`
8. 写 remark / 返回时间 / sign / 状态

### 8.5 通知重试机制

调度任务分两层：

- `NoticeCallbackDispatcherTask`：先统计每个 `sendNum` 对应多少通知待发
- `NoticeCallbackTask`：按次数扫描并并发发送

这说明通知重试不是简单 while 重试，而是 **分层调度 + 批量扫描**。

### 8.6 为什么这里还要 `checkAndUpdate`

代码注释已经说明：

- 可能出现正常业务线程和补偿任务同时发通知
- 一个成功，一个失败
- 如果直接覆盖，失败任务可能把成功状态写坏

所以这里专门做了“如果已经成功，就不要再回写失败状态”的保护。

### 8.7 通知实现的面试说法

> 这个项目的通知是完整的异步通知中心模式。支付、退款、提现、签约等业务完成后，不直接同步回调，而是先更新通知单，再通过 MQ 或线程池异步发送。通知发送时会动态补签名、记录响应结果，并通过定时扫描补偿失败通知。这样既降低了主流程耦合，也保证了通知可追踪、可重试、可恢复。

---

## 9. 撤单 / 冲正链路：项目里是怎么做的

这个模块很容易被忽略，但面试官往往很喜欢问。

### 9.1 任务入口

入口在：

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/pay/ReverseOrderTask.java`

它会：

1. 扫描最近 24 小时、70 秒前创建的订单
2. 只处理 `pay_state = 5` 的订单（需要撤单的状态）
3. 对每个订单按渠道选对应撤单服务
4. 使用 `TimedLock` 防止同一订单并发撤单

### 9.2 撤单不是单一动作，而是分两种

在 `AbstractReverseService` 里有两种典型路径：

#### 路径 A：订单未真正支付成功

直接撤单：

- 更新充值单状态为取消
- 更新分发表 / 业务单状态

#### 路径 B：订单已经支付成功，需要“撤单 + 退款”

执行：

1. 用户账和商户账做逆向处理
2. 生成退款单
3. 冻结退款资金
4. 将退款单推进到冲正退款状态
5. 更新原订单为已撤单/已退款

### 9.3 为什么要有撤单任务

因为支付链路常见问题是：

- 用户支付超时
- 业务层判断失败，但第三方稍后成功
- 风险拦截后需要关闭支付
- 通道异常导致需要主动冲正

撤单任务就是处理这些“支付末态不干净”的场景。

### 9.4 撤单实现的面试说法

> 撤单模块处理的是支付链路中的异常收尾。系统会扫描需要撤单的订单，根据不同渠道调用关闭/撤销接口。如果订单尚未支付成功，直接关闭即可；如果订单实际上已经支付成功，则会进入“撤单退款”分支，生成退款单并完成账务逆操作。这个模块本质上是支付异常场景下的补偿机制。

---

## 10. 签约 / 解约链路

签约不是主交易，但在支付系统里也很重要。

### 10.1 任务入口

- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/agreement/AgreementQueryTask.java`
- `taskcenter-web/src/main/java/com/shunwang/taskcenter/task/agreement/AgreementCancelTask.java`

从代码看，它会扫描近两天的签约单，按：

- 支付宝签约中
- 支付宝解约中
- 微信签约中
- 微信解约中

分别执行查询。

### 10.2 签约公共处理逻辑

核心在：

- `taskcenter-service/src/main/java/com/shunwang/taskcenter/service/impl/pay/agreement/AbstractAgreementService.java`

流程是：

1. 根据 externalSignNo 查询原签约数据
2. 校验当前签约状态是否合法
3. 更新签约状态
4. 如配置了“覆盖其他渠道签约”，则自动解约其他渠道
5. 生成/更新通知单
6. 发送通知

### 10.3 签约逻辑为什么值得讲

因为它体现出这个系统并不只是“支付订单中心”，而是一个更完整的 **支付账户生命周期中心**。

它不仅处理交易，还处理：

- 支付关系绑定
- 支付能力开通
- 多渠道签约覆盖

---

## 11. Global（海外）链路

从目录结构看，项目专门把海外业务单独分了一套：

- global order query
- global refund
- global refund query
- global notice

### 11.1 为什么单独拆 global

大概率是因为海外业务和国内业务在这些方面不同：

- 币种
- 回调字段
- 渠道路由
- 商户体系
- 通知参数

所以没有硬塞进国内逻辑，而是单独维护一套 parallel 结构。

### 11.2 设计特点

Global 逻辑整体上和国内支付域一致：

- 也有任务入口
- 也有退款、查单、通知
- 也有 register factory

说明作者在架构上采用了：

> 国内一套、海外一套，但模式保持一致。

这是一种比较稳妥的设计。

---

## 12. 对账、报表、初始化、资料变更这些周边能力

如果面试官问“除了支付主链路还有什么”，你可以顺着讲这些：

### 12.1 对账

已经单独整理，见：

- `docs/settle-interview-guide.md`

### 12.2 报表 / 取消任务

代码里有：

- 报表任务
- 取消任务
- 初始化数据任务

这些通常和渠道报送、商户资料补录、流水校验有关。

### 12.3 商户资料变更 / 结算卡变更

代码里有不少：

- `merchant/modify`
- `merchant/bankcard`
- `BaseSettlementCardAlterationTask`

说明系统也承担一部分“商户渠道侧资料同步”的任务。

### 12.4 监控任务

代码里有：

- `PayMonitorTask`
- `TradeErrorMonitorTask`
- `DetailToBalanceMonitorTask`
- `OrderToDetailMonitorTask`

这说明项目不只是执行任务，还内置了任务结果监控和异常告警。

---

## 13. 这个项目里反复出现的通用机制

这是面试里最值得上升总结的一部分。

### 13.1 锁

常见用途：

- 同一订单防并发处理
- 同一渠道同一账期防重复对账
- 同一支付单防重复撤单

### 13.2 事务

常见用途：

- 订单状态 + 账户变更一起提交
- 退款状态 + 解冻/扣款一起提交
- 提现状态 + 账户变更一起提交

### 13.3 MQ / 异步线程池

常见用途：

- 通知异步发送
- 批量任务并发执行
- 渠道回调或补偿解耦

### 13.4 状态机

常见对象：

- PayList
- RefundList
- OutCashOrder
- NoticeMsg
- AccountAgreement

### 13.5 补偿机制

常见形式：

- 定时查单
- 定时查退款
- 定时查提现
- 定时补通知
- 定时对账
- 撤单补偿

这说明整个系统是明显的：

> “同步触发 + 异步补偿 + 最终一致” 的金融任务架构。

---

## 14. 从面试角度看，这个项目最大的亮点是什么

我建议你重点讲 4 个亮点。

### 14.1 金融场景抽象做得比较统一

虽然渠道很多，但通过 task + abstract service + channel service，把差异封装进子类，公共流程保持稳定。

### 14.2 资金类业务有明确事务意识

不是单纯改订单状态，而是把：

- 用户账
- 商户账
- 冻结/解冻
- 通知

一起考虑进去。

### 14.3 通知体系比较成熟

不是同步 callback，而是通知单 + 异步发送 + 重试补偿。

### 14.4 整个系统强依赖补偿而非强依赖实时回调

这非常符合真实支付系统特点。

---

## 15. 这个项目里最可能被问到的问题

下面这些问题你可以直接准备。

### Q1：这个项目整体是做什么的？

**答：**

它本质上是一个支付任务中心。核心职责不是收银前台，而是支付后的异步处理，包括查单、退款、提现、通知、撤单、签约、对账等金融任务。它通过定时任务和渠道服务把第三方结果同步回内部系统，并完成账户变更和业务回调。

### Q2：为什么这么多逻辑都用定时任务？

**答：**

因为支付系统天然存在异步和不确定性，比如第三方回调延迟、回调丢失、渠道接口抖动、业务系统短暂不可用。定时任务是一种补偿手段，可以把未完成状态不断推进到最终一致。

### Q3：支付成功后为什么不直接回调，而是走通知单？

**答：**

因为通知也会失败。先落通知单再异步发送，可以支持重试、审计和补偿，也能避免业务主流程被对方接口拖慢。

### Q4：提现失败为什么要生成退款单？

**答：**

因为提现失败本质上是一次资金回退。把它统一建模成退款单，能复用退款体系的状态机、账户处理和通知逻辑，避免出现另一套独立的回退逻辑。

### Q5：撤单和退款是什么关系？

**答：**

撤单不一定退款。如果订单还没实际支付成功，撤单只是关闭订单；如果订单已经成功，就要进入“撤单退款”分支，实际上做的是冲正退款。

### Q6：这套系统最大的复杂点在哪？

**答：**

最大的复杂点不是调用第三方接口，而是内部状态、账户、通知的一致性。因为支付、退款、提现每一步都不是单一动作，而是多个对象联动，所以必须靠事务、状态机和补偿机制保证最终正确。

---

## 16. 这个项目最容易出的问题

### 16.1 状态被重复推进

比如：

- 回调到了
- 定时查单也到了
- 补偿任务也在跑

如果没有锁和状态检查，就会重复入账或重复退款。

### 16.2 异步通知成功/失败互相覆盖

项目里已经专门写了 `checkAndUpdate()` 来避免这个问题，说明这是实际遇到过的。

### 16.3 渠道处理中“受理成功 != 最终成功”

尤其退款、提现，经常先返回已受理，后面还要继续查结果。

### 16.4 资金变更和订单变更不一致

如果没有事务保护，很容易出现：

- 单状态成功，账户没改
- 账户改了，通知没发
- 提现失败但冻结没解

### 16.5 大量重试带来的幂等问题

任务反复跑本身没问题，但前提是：

- 加锁
- 状态判断
- 唯一键或版本控制

否则越补偿越乱。

---

## 17. 如果让我继续优化，我会怎么做

### 17.1 给所有资金任务加统一执行记录表

记录：

- 任务名
- 扫描条件
- 执行批次
- 成功数 / 失败数
- 首次失败原因

### 17.2 完善异常分级

把异常拆成：

- 可重试异常
- 不可重试异常
- 需要人工介入异常

### 17.3 加强幂等保护

例如：

- 通知更新增加版本号
- 账户变更增加幂等流水号校验
- 撤单/退款/提现都加更明确的状态前置检查

### 17.4 把渠道能力进一步标准化

把每个渠道的：

- 发起请求
- 查询请求
- 状态映射
- 错误码映射

收敛成更清晰的 SPI。

### 17.5 增加可观测性指标

重点监控：

- 查单成功率
- 通知成功率
- 退款超时数量
- 提现失败率
- 对账差异数量

---

## 18. 你可以怎么做 3 分钟面试表达

> 这个项目我理解它不是单一支付接口项目，而是一个支付任务中心。它把支付后的异步处理统一收敛到了任务系统里，覆盖查单、退款、提现、通知、撤单、签约、对账等多个资金域。整体设计上用了大量模板方法和注册工厂，把公共流程和渠道差异拆开。比如支付查单会定时扫描待确认订单，查询第三方结果后在事务里完成订单状态、用户资金账和商户账的联动，再异步发通知；退款是发起和查询分离的状态机模型；提现则多了黑名单、余额、时间窗等前置校验，失败时还会生成退款单做资金回退；通知模块则采用通知单 + 异步发送 + 扫描重试的方式保证可补偿。这个项目让我印象最深的是它并不依赖单次同步成功，而是强依赖补偿机制和最终一致性，这很符合真实支付系统的工程特点。

---

## 19. 和对账文档的关系

这份文档是“项目总览版”，重点在整个平台的业务域。

如果面试官继续深问对账，你再接着展开：

- `docs/settle-interview-guide.md`

这样你就形成了两层回答：

1. **项目全貌**：支付、退款、提现、通知、撤单、签约、global、对账
2. **单点深挖**：对账专项细节
