# `OrderApiController.report` 风控数据上报逻辑梳理

## 1. 目标方法定位

- 控制器方法：`swrisk-api/src/main/java/com/shunwang/swrisk/controller/OrderApiController.java`
- 接口路径：`/api/order/report`
- 方法签名：`public Result report(BaseDataVo<OrderInfo> vo)`
- 注解使用的是 `@RequestMapping("/report")`，没有限定 HTTP Method，因此从 Spring MVC 语义上看，GET/POST 等都可能命中，具体还取决于外围网关或配置。
- 方法参数没有 `@RequestBody`，说明请求如何绑定到 `BaseDataVo<OrderInfo>`，取决于当前项目的参数绑定方式（表单、query 参数、或统一包装层）。

---

## 2. 入参结构

`report` 接口接收的是 `BaseDataVo<OrderInfo>`，其中：

- `service`：服务名
- `businessKey`：业务标识
- `time`：请求时间
- `data`：业务数据，实际会被反序列化成 `OrderInfo`
- `sign`：签名

但**在 `report` 方法内部，真正直接使用的只有 `data`**：

- 通过 `vo.getBusEntity(OrderInfo.class)` 把 `data` JSON 反序列化为 `OrderInfo`
- `service / businessKey / time / sign` 在该方法内**没有被显式校验或使用**
- 如果签名校验存在，大概率在拦截器、过滤器或网关层处理，而不是这里

---

## 3. 方法整体流程总览

`report` 的主流程可以概括为：

1. 启动 `StopWatch` 统计耗时
2. 从 `BaseDataVo.data` 解析出 `OrderInfo`
3. 异步同步 `partner/service` 基础信息
4. 初始化 `userType` / `userName`
5. 先查白名单
6. 再查黑名单
7. 未命中名单时，进入规则引擎风控
8. 根据规则结果返回 `TradeResult`
9. 最后记录整个接口耗时日志

对应伪代码如下：

```java
StopWatch sw = new StopWatch();
sw.start();

OrderInfo orderInfo = vo.getBusEntity(OrderInfo.class);
sync(orderInfo);
initUserInfo(orderInfo);

TradeResult result = new TradeResult();

if (checkInWhiteList(orderInfo)) {
    return 白名单直接放行;
}

if (checkInBlackList(orderInfo)) {
    return 黑名单直接拦截;
}

orderInfo.setTimeCreate(new Date());
result = orderInfoService.processOrderInfo(orderInfo);
return SUCCESS(result);
```

---

## 4. 第一步：耗时统计

方法一开始就创建并启动：

```java
StopWatch sw = new StopWatch();
sw.start();
```

然后在 `finally` 中：

- 调用 `sw.stop()`
- 打印日志：`交易report请求耗时[xxx]`

这意味着：

- 无论正常返回、白名单返回、黑名单返回、异常返回，都会记录耗时
- 统计的是**整个控制器方法总耗时**，不只是规则引擎耗时

---

## 5. 第二步：把 `data` 反序列化成 `OrderInfo`

代码：

```java
OrderInfo orderInfo = (OrderInfo) vo.getBusEntity(OrderInfo.class);
```

这里的实际行为：

- `BaseDataVo.getBusEntity()` 内部调用 `GsonUtil.jsonToBean(data, clazz)`
- 即把 `vo.data` 这段 JSON 转成 `OrderInfo`

### 5.1 这个阶段的关键细节

- **没有空值保护**：`report` 方法没有判断 `vo == null`
- **没有空对象保护**：也没有判断 `orderInfo == null`
- 因此一旦：
  - `vo` 本身为 `null`
  - `data` 为空
  - `data` 反序列化失败
  - `orderInfo` 缺少关键字段导致后续访问空指针
  都可能在后续步骤中触发异常
- 但异常会被 `catch (Exception e)` 吃掉，最终仍返回 `ResultGenerator.newEmptySuccessResult()`，也就是**外层返回仍然是成功**

这点非常重要：

- **接口内部异常 ≠ 接口外层返回失败**
- 这会让调用方看到 `code=0, message=SUCCESS`，但实际风控可能并未正常执行

---

## 6. 第三步：异步同步基础配置 `sync(orderInfo)`

调用：

```java
sync(orderInfo);
```

这个逻辑不会阻塞主流程，它只是提交一个异步任务。

### 6.1 `sync` 的行为

- 如果 `orderInfo == null`，直接返回
- 否则通过 `CustomExecutorService.execute(new Task(orderInfo))` 异步执行

### 6.2 线程池实现

`CustomExecutorServiceImpl` 使用的是：

```java
Executors.newCachedThreadPool()
```

也就是说：

- 这是一个缓存线程池
- 理论上会按需扩容线程
- 不会等待该异步任务执行完，主流程继续往下走

### 6.3 `Task.run()` 做了什么

异步任务里有两段同步行为：

#### 1）同步合作方信息 `partner`

- 如果 `partnerId` 为空，整个任务直接返回，不再继续做后面的 `service` 同步
- 如果 `partnerId` 不为空：
  - 调用 `payGatewayService.syncPartnerInfo(orderInfo.getPartnerId())`
  - 如果本地已有 `ConfigPartner`，直接返回
  - 如果本地没有，则调用外部 `SwpayServiceClient` 查询并落库

#### 2）同步网关/业务信息 `service`

- 只有在 `serviceId != null` 时才会继续
- 调用 `payGatewayService.syncGatewayInfo(orderInfo.getServiceId())`
  - 如果本地已有 `ConfigService`，直接返回
  - 没有则调用外部 `SwpayServiceClient` 查询并落库

### 6.4 这个异步同步对主流程的影响

- **只起到“补数据/预热配置”的作用**
- 不参与当前请求的返回结果计算
- 就算同步失败，也只是记日志，不会影响本次 `report` 的结果返回

### 6.5 一个隐藏细节

因为 `Task.run()` 开头写的是：

```java
if (StringUtil.isEmpty(orderInfo.getPartnerId())) {
    return;
}
```

所以：

- 只要 `partnerId` 为空，后面的 `serviceId` 即使有值，也**不会执行 `syncGatewayInfo`**
- 这是当前代码的实际行为，不是“分别独立同步”

---

## 7. 第四步：初始化用户维度 `initUserInfo(orderInfo)`

这一步非常关键，因为：

- 后续白/黑名单匹配依赖 `userName`
- 后续风控规则很多也依赖 `userType` / `userName`

### 7.1 目的

根据 `serviceId`、`openId`、`modeKey`、`bankCardNo`、`passportName`、`outCashUserType` 等字段，推导两个核心值：

- `orderInfo.userType`
- `orderInfo.userName`

### 7.2 分支一：`serviceId` 属于 1045 / 1049 / 1068 / 1071

即：

- `SERVICE_ID_32 = 1045`
- `SERVICE_ID_39 = 1049`
- `SERVICE_ID_59 = 1068`
- `SERVICE_ID_62 = 1071`

这几个服务走的是“优先看 `openId`，再看银行卡，否则回退通行证”的逻辑。

#### 情况 A：`openId` 非空

先根据 `modeKey` 推导 `userType`：

- `modeKey` 为空，或属于微信集合 `wxModeKeyList` → `WEIXIN_ACCOUNT(2)`
- 属于支付宝集合 `aliModeKeyList` → `ALIPAY_ACCOUNT(3)`
- 属于 QQ 集合 `qqModeKeyList` → `QQ_ACCOUNT(4)`
- 属于京东集合 `jdModeKeyList` → `JD_ACCOUNT(11)`
- 都不匹配 → `PASSPORT(1)`

然后：

- `userName = openId`

#### 情况 B：`openId` 为空，但 `bankCardNo` 非空

- `userType = BANK_ACCOUNT(5)`
- `userName = bankCardNo`

#### 情况 C：`openId` 和 `bankCardNo` 都为空

- `userType = PASSPORT(1)`
- `userName = passportName`

### 7.3 分支二：`serviceId == 1082`

即：`SERVICE_ID_71 = 1082`

逻辑：

- 如果 `outCashUserType == "2"`（支付宝提现）
  - `userType = ALIPAY_ACCOUNT(3)`
- 否则
  - `userType = BANK_ACCOUNT(5)`

然后统一：

- `userName = passportName`

这个点比较特别：

- `userType` 表示的是提现账号类型（支付宝/银行卡）
- 但 `userName` 却不是账号本身，而是 `passportName`
- 这会直接影响名单匹配和规则查询维度

### 7.4 分支三：`serviceId == 1135` 或 `1077`

- `SERVICE_ID_35 = 1135`
- `SERVICE_ID_77 = 1077`

逻辑：

- `userType = WEIXIN_ACCOUNT(2)`
- `userName = openId`

### 7.5 默认分支

其余所有服务：

- `userType = PASSPORT(1)`
- `userName = passportName`

### 7.6 初始化结果对后续的直接影响

这一步执行完后，后续逻辑会默认依赖：

- `passportName`
- `userName`
- `receivePassportName`

尤其是名单匹配和风控策略检索。

如果这一步推导错了，后面的白名单、黑名单、规则命中都会偏掉。

---

## 8. 第五步：白名单优先拦截链路 `checkInWhiteList(orderInfo)`

调用：

```java
if (checkInWhiteList(orderInfo)) {
    ...
    return ResultGenerator.newResult(ResultCode.SUCCESS, result);
}
```

白名单类型实际是：

- `RiskFactorListEnum.FactorType.PAY_WHITE_LIST.getType()`
- 即交易白名单

### 8.1 白名单匹配的底层入口

本质上调用的是：

```java
checkInList(orderInfo, PAY_WHITE_LIST)
```

### 8.2 `checkInList` 的具体匹配步骤

#### 第 1 步：取对应类型的名单 Map

```java
Map<String, RiskFactorList> listMap = riskFactorListService.findRiskFactorListMap(listType);
```

这里返回的是：

- `key = factorKey`
- `value = RiskFactorList`

并且该 Map 是有缓存的。

#### 第 2 步：准备 3 个候选匹配值

方法内部固定构造了一个 `params`：

- `PN|` → `orderInfo.passportName`
- `UN|` → `orderInfo.userName`
- `RPN|` → `orderInfo.receivePassportName`

也就是说，白/黑名单只会从这 3 个维度匹配：

1. 下单人/通行证名
2. 支付人/用户标识
3. 收款人/收款通行证名

#### 第 3 步：逐项匹配 Map

对每个候选值执行：

```java
RiskFactorList pojo = listMap.get(entry.getValue());
```

含义是：

- 用 `passportName` / `userName` / `receivePassportName` 的**实际值**，直接去 `factorKey` Map 里查
- 是**精确匹配**，不是模糊匹配
- 没有 `trim`
- 没有大小写归一化
- 没有空串保护，空值会直接 `get(null)` 或 `get("")`

#### 第 4 步：校验该名单项是否作用于当前字段

即：

```java
if (pojo.getFilterParams().contains(entry.getKey()))
```

含义：

- 即使 `factorKey` 命中了，还要看这条名单记录的 `filterParams` 是否声明包含当前字段标记
- 标记值分别是：
  - `PN|`
  - `UN|`
  - `RPN|`

举例：

- 如果名单里存了某个 `factorKey = abc123`
- 但它的 `filterParams` 只包含 `PN|`
- 那么只有当前遍历到 `passportName=abc123` 时才算命中
- 如果是 `userName=abc123`，则不会算命中

#### 第 5 步：临时名单过期判断

```java
if (pojo.getExpType().equals(EXP_TYPE_TEMP)
    && DateUtil.compare(new Date(), pojo.getTimeExp(), ONE_MILLI_SECOND) > 0) {
    continue;
}
```

含义：

- 如果这条名单是“临时名单”
- 且当前时间已经晚于 `timeExp`
- 则视为过期，不命中

#### 第 6 步：校验合作方范围 `partnerId`

```java
if (pojo.getPartnerId().equalsIgnoreCase("0|")
    || pojo.getPartnerId().contains(orderInfo.getPartnerId() + "|"))
```

含义：

- `0|` 代表对所有产品线/合作方生效
- 否则要求名单记录中的 `partnerId` 字符串里包含当前订单的 `partnerId + "|"`

例如：

- 名单配置 `partnerId = "1001|1002|"`
- 当前订单 `partnerId = "1002"`
- 则命中

#### 第 7 步：命中后立即返回

- 一旦某个字段命中名单，`result = true` 并 `break`
- 不会继续检查后面的字段

### 8.3 白名单命中后的返回结构

命中白名单后，不会再走黑名单，也不会再走规则引擎。

返回的 `TradeResult` 被设置为：

- `levelMsg += ":用户在白名单内"`
- `busCode = 2000`（`TradeResult.BusCode.WHITE_LIST_CODE`）

然后外层返回：

- `Result.code = 0`
- `Result.message = "SUCCESS"`
- `Result.data = TradeResult`

### 8.4 白名单命中后的特点

- **直接放行**
- **不落订单风控规则结果**
- **不进入规则引擎**
- **不会检查黑名单**（因为已经提前 return）

---

## 9. 第六步：黑名单检查 `checkInBlackList(orderInfo)`

黑名单和白名单共用同一套 `checkInList` 逻辑，只是名单类型换成：

- `RiskFactorListEnum.FactorType.PAY_BLACK_LIST.getType()`

### 9.1 黑名单命中后的 `TradeResult`

命中后，代码显式设置：

- `code = "1"`
- `msg = "DANGER/禁止" 对应的风险描述`
- `level = 2`
- `levelMsg = "禁止:用户在黑名单内"`

然后外层返回：

- `Result.code = 2001`
- `Result.message = "黑名单"`
- `Result.data = TradeResult`

### 9.2 黑名单返回特点

- 这是**接口级别的业务失败**
- 外层 `Result.code` 不是 0，而是 `2001`
- 不进入规则引擎
- 不走订单保存逻辑

### 9.3 白名单优先于黑名单

顺序是：

1. 先查白名单
2. 白名单命中就 return
3. 只有白名单没命中，才查黑名单

所以如果同一个人理论上同时存在于白名单和黑名单：

- **以白名单为准**
- 因为白名单更早返回

---

## 10. 第七步：进入规则引擎前，补齐下单时间

只有在：

- 不在白名单
- 不在黑名单

时，才会执行：

```java
orderInfo.setTimeCreate(new Date());
```

这个时间很重要，后续很多规则统计窗口都依赖它。

例如：

- 查询最近 N 秒/分钟/天的数据
- 构造模型命中记录
- 保存订单

注意：

- 白名单/黑名单直接返回时，不会设置 `timeCreate`
- 所以名单直返路径和规则引擎路径在订单对象完整度上并不完全一致

---

## 11. 第八步：进入 `orderInfoService.processOrderInfo(orderInfo)`

这是整个风控核心。

### 11.1 表面设计意图

`OrderInfoServiceImpl.processOrderInfo()` 注释表达的意图是：

- 建一个超时任务（默认 100ms）
- 再建一个真正的风控任务
- 如果 100ms 内完成则返回风控结果
- 如果超时则快速返回一个默认结果

### 11.2 实际代码

```java
final CompletableFuture<TradeResult> timeOut = failAfter(Duration.ofMillis(processTimeout));
final CompletableFuture<TradeResult> responseFuture = CompletableFuture.supplyAsync(() -> ruleManager.processor(orderInfo), executor);
responseFuture.acceptEither(timeOut, this::dealReturn).exceptionally(...);
return responseFuture.get();
```

### 11.3 当前代码的实际行为

这里有一个非常重要的实现细节：

- `acceptEither(timeOut, ...)` 的结果**没有被 return**
- 真正返回的是 `responseFuture.get()`

这意味着：

- **即使超时 Future 在 100ms 后失败了，也不会直接让 `report` 返回默认值**
- 控制器线程仍然会继续阻塞等待 `ruleManager.processor(orderInfo)` 执行完成

换句话说：

- 注释里写的是“超时后直接返回正常结果”
- 但当前代码真实效果是：**超时机制没有真正截断主流程等待时间**

### 11.4 异常时的返回

如果 `responseFuture.get()` 抛异常：

- 记一条 warn 日志
- 返回 `new TradeResult()`（默认对象）

默认 `TradeResult` 的典型特征是：

- `code = null`
- `msg = null`
- `busCode = null`
- `level = 0`
- `levelMsg = null`

---

## 12. 第九步：`TradeRuleManager.processor(orderInfo)` 规则总控

这是规则引擎真正的入口。

整体职责：

1. 找出当前 `partnerId + serviceId` 对应的所有启用规则
2. 并行执行每条规则对应的模型判断
3. 汇总各规则结果，选出最严重的一条
4. 根据结果设置订单状态
5. 异步保存订单

---

## 13. 第十步：查询当前订单适用的规则

代码：

```java
List<ConfigRules> ruleList = rulesService.findByPartnerIdAndServiceId(orderInfo.getPartnerId(), orderInfo.getServiceId());
```

### 13.1 查询条件

实际查询条件是：

- `partner_id = orderInfo.partnerId`
- `service_id = orderInfo.serviceId`
- `state = OPEN`

即：

- 只取当前合作方
- 只取当前业务服务
- 只取启用状态的规则

### 13.2 缓存特征

`findByPartnerIdAndServiceId` 带缓存，所以重复请求同一个 `partnerId + serviceId` 的规则列表时，不一定每次都打数据库。

### 13.3 如果没有任何规则

如果 `ruleList` 为空：

- 后续不会报错
- 汇总后得到默认 `TradeResult`
- 最终订单状态会被设置为 `CREATE(1)`
- 然后仍然会异步保存订单

也就是说：

- **“没有配置任何模型” != 不保存订单**
- 系统依然会把订单作为“正常创建”处理

---

## 14. 第十一步：并行执行每一条规则模型 `processorModel`

对每个 `ConfigRules` 都会创建一个异步任务：

```java
CompletableFuture.supplyAsync(() -> processorModel(orderInfo, partnerRule), executor)
```

然后 `CompletableFuture.allOf(tasks).get()` 等待全部完成。

### 14.1 这一级并发的粒度

- 一个订单命中的**多条规则**是并行执行的
- 每条规则内部的**多条策略**也会继续并行执行

所以这是一个两层并发结构：

1. 规则并发
2. 策略并发

---

## 15. 第十二步：单条规则 `processorModel(orderInfo, configRules)`

这一步是“某一条规则/某一个模型”的执行逻辑。

### 15.1 先校验模型是否启用

先根据 `configRules.modelId` 查 `ConfigModel`：

- 模型不存在 → 直接返回默认 `TradeResult`
- 模型是关闭状态 → 直接返回默认 `TradeResult`

所以：

- 即使规则是启用状态
- 只要它关联的模型关闭了
- 这条规则也不会生效

### 15.2 解析规则里的策略配置 `strategyConf`

`configRules.strategyConf` 会被解析成：

```java
List<Map<String, String>> confList
```

每个 Map 通常包含这些 key：

- `key`：策略 Spring Bean 名 / 策略标识
- `T`：时间窗口
- `N`：次数阈值
- `PM`：单笔金额过滤阈值
- `AM`：累计金额阈值

### 15.3 并行执行每条策略

对每个策略配置项：

```java
strategyProcess(orderInfo, map.get("key"), map)
```

如果策略执行异常：

- 记录日志
- 返回一个默认 `StrategyResult()`
- 默认 `code=0`，表示“不违规”

这意味着：

- **单条策略执行失败，默认按未命中处理**
- 不会中断整条规则

### 15.4 统计违规策略个数

所有策略执行完以后，会遍历每个 `StrategyResult`：

- `temp != null && !temp.isOk()` 视为该策略违规
- 每违规 1 条，`count + 1`
- 同时把 `temp.getMsg()` 追加到 `conditionErr`

### 15.5 异步处理违规策略记录

一旦某个策略违规，会立即异步执行：

```java
TradeStrategyProcessor.afterCall(orderInfo, result.getParserName(), result, configRules)
```

默认情况下，这一步会落一条 `breach_strategy_item` 记录。

### 15.6 对比模型阈值 `strategyThreshold`

最后判断：

```java
if (count >= configRules.getStrategyThreshold())
```

即：

- 不是“命中 1 条策略就一定算模型违规”
- 而是要看该规则配置的阈值 `strategyThreshold`
- 只有违规策略数达到/超过阈值，模型才判定为违规

### 15.7 模型违规时如何构造 `TradeResult`

模型违规后会设置：

- `level = configRules.action`
- `levelMsg = 风险级别中文 + ":" + conditionErr`
- `code = "1"`
- `msg = "RISK"`

其中 `action` 的语义是：

- `1`：预警
- `2`：禁止
- `3`：预警提示

### 15.8 模型违规后的异步落库/告警

一旦模型违规，会异步执行：

```java
afterProcess(orderInfo, configRules, count, conditionErr)
```

最终会做两件事：

1. 保存 `breach_model_item`
2. 触发实时告警 `alarmService.ruleAlarm(...)`

---

## 16. 第十三步：单条策略是如何判定违规的

`TradeStrategyProcessor.call()` 会根据 `key` 取出对应的策略 Bean，然后执行其 `processor()`。

大多数交易策略实现继承 `AbstractTradeStrategyParser`，所以其通用行为非常值得看。

### 16.1 通用预检查 `preCheck`

默认会校验 `T`（时间窗口）：

- 如果 `T != pd`
- 并且 `T` 对应秒数大于 1 天
- 则该策略直接返回默认 `StrategyResult()`，视为不执行

也就是说：

- 通用基类默认**不允许统计窗口超过 1 天**
- `pd` 是特殊值，表示“当天”

### 16.2 金额类的额外预检查 `preCheckMoney`

部分金额类策略会先比较：

- 如果当前订单金额本身已经大于配置金额阈值 `AM`
- 则直接返回违规

### 16.3 通用查询条件构造 `buildQuery`

基类会根据不同策略声明的 `getQueryParams()` 去构造 `OrderInfoQuery`。

可参与查询的字段包括：

- `passportName`
- `partnerId`
- `serviceId`
- `tradeType`
- `state`
- `idCardNo`
- `userName`
- `userType`
- `receivePassportName`
- `T`（时间窗口）
- `PM`（最低金额过滤）

### 16.4 一个非常关键的细节：历史订单查询默认排除风控禁止单

当 query 包含 `STATE` 条件时，实际 SQL 使用的是 `breach_where`：

```sql
and t.state != #{state}
```

而代码传入的是：

- `state = ORDER_STATE_RISK (2)`

所以很多策略统计历史数据时，实际含义是：

- **统计历史订单，但排除已经被风控禁止的订单**

### 16.5 时间窗口的实际 SQL 含义

如果 `T = pd`：

- `begin = 今日 00:00:00`
- `end = 当前时间`

如果 `T = 秒数`：

- `begin = 当前时间 - T 秒`
- `end = 当前时间`

最终 SQL 是：

- `time_create >= begin`
- `time_create < end`

### 16.6 金额过滤 `PM`

如果策略声明了 `PM`，则 SQL 里会加：

- `trade_money >= PM`

这表示：

- 先过滤掉小额订单
- 再做次数/金额统计

### 16.7 次数类策略 `processorCount`

逻辑是：

1. 查历史命中订单数 `count`
2. 判断 `count >= N`
3. 如果成立，则返回违规的 `StrategyResult`

### 16.8 累计金额类策略 `processorAmount`

逻辑是：

1. 先查历史金额总和 `amount`
2. 再把**当前订单金额**加进去
3. 判断 `amount >= AM`

也就是说：

- 金额策略比较的是“历史累计 + 当前单”
- 不是只看历史数据

### 16.9 违规策略的落库内容

基类默认会生成 `BreachStrategyItem`，核心字段包括：

- `ruleId / ruleKey / modelId / serviceId / partnerId`
- `strategyKey`
- `orderNo`
- `strategyNameReal`（实际命中描述）
- `tradeMoney / tradeType`
- `factorKey = orderInfo.userName`
- `factorType = orderInfo.userType`

---

## 17. 第十四步：多条规则结果如何汇总

所有 `processorModel` 的结果会组成 `List<TradeResult>`，然后：

```java
Arrays.stream(tasks)
    .map(CompletableFuture::join)
    .sorted()
    .collect(Collectors.toList())
```

最后取第一条作为最终结果：

```java
result = collect.get(0)
```

### 17.1 `TradeResult.compareTo()` 的排序规则

排序规则是：

- `level=2`（禁止）优先级最高，排最前
- 其余情况按 `level` 倒序排

也就是大致优先级：

1. `2` 禁止
2. `3` 预警提示
3. `1` 预警
4. `0` 正常

### 17.2 一个细节：同级别结果的比较实现并不稳定

`compareTo()` 在级别相同的时候返回的是 `1`，不是 `0`。

这意味着：

- 同级别结果之间的排序**不完全稳定**
- 如果有多条规则产生同一风险级别，最终取到哪一条作为第一条结果，理论上可能受排序实现影响

---

## 18. 第十五步：根据最终风险级别设置订单状态

汇总出最终 `TradeResult` 后，会设置订单状态：

- `level == 2` → `ORDER_STATE_RISK = 2`
- `level == 3` → `ORDER_STATE_WARN_TIPS = 3`
- 其他情况 → `ORDER_STATE_CREATE = 1`

### 18.1 一个重要细节

这里没有单独处理 `level == 1`（预警）。

所以当前实现下：

- **预警（1）不会把订单状态置成风险态**
- 而是会落成 `CREATE(1)`

也就是说：

- `预警提示(3)` 会改状态
- `预警(1)` 不改状态
- `禁止(2)` 会改状态

这是当前代码非常关键的业务差异点。

---

## 19. 第十六步：异步保存订单 `saveProcess(orderInfo)`

在规则总控里，订单保存也是异步做的：

```java
CompletableFuture.runAsync(() -> saveProcess(orderInfo), executor)
```

### 19.1 `saveProcess` 内部逻辑

它又套了一层异步：

```java
CompletableFuture.runAsync(() -> orderInfoService.saveOrUpdate(orderInfo), executor)
```

然后最多等待 1000ms。

即：

- 主规则流程已经拿到结果并准备返回
- 订单保存是异步的
- 保存超时/失败不会影响本次接口返回

### 19.2 `saveOrUpdate` 的新增逻辑

如果数据库里不存在同 `orderNo` 订单：

1. 若 `ip` 非空，则调用 `configIpLocationService.getLocation(ip)`
2. 把返回的 `cityKey` 回填到订单
3. 执行 `orderInfoMapper.save(orderInfo)` 插入

也就是说：

- 新订单会尝试做 IP → 城市映射
- 再入库

### 19.3 `saveOrUpdate` 的更新逻辑

如果数据库里已经存在同 `orderNo` 订单：

1. `orderInfo.setTimeEdit(new Date())`
2. `orderInfoMapper.update(orderInfo)`

但是这里有一个非常关键的实现细节：

- `saveOrUpdate` 查询到了旧订单 `temp`
- **却没有把 `temp.id` 回填给 `orderInfo`**
- 而 `orderInfoMapper.update` 的 SQL 条件是：

```sql
where id = #{id}
```

所以按当前代码直读，更新分支存在明显风险：

- 如果调用方传进来的 `orderInfo.id` 本来就是空
- 那么 `update` 的 `where id = null`
- 理论上将无法命中任何记录

也就是说：

- **当前 `report -> processOrderInfo -> saveOrUpdate` 这条链路，在“已有订单更新”场景下，可能更新不到任何数据**

这属于当前代码里一个很值得重点关注的实现细节。

---

## 20. 第十七步：模型违规时落什么数据

当某条规则模型被判定违规时，会生成 `BreachModelItem`。

关键字段包括：

- `modelBreachNo = "PR" + ruleId + "ON" + orderNo`
- `passportName = orderInfo.passportName`
- `factorKey = orderInfo.userName`
- `type = orderInfo.userType`
- `orderNo`
- `tradeMoney`
- `tradeType`
- `action = configRules.action`
- `strategyThreshold`
- `breachItems = count`
- `remark = conditionErr`

然后：

1. 保存到 `breach_model_item`
2. 调用告警服务发送实时告警

---

## 21. 第十八步：`report` 最终返回给调用方的几种结果

### 21.1 白名单命中

外层：

- `Result.code = 0`
- `Result.message = SUCCESS`

内层 `TradeResult`：

- `busCode = 2000`
- `levelMsg` 追加“用户在白名单内”

### 21.2 黑名单命中

外层：

- `Result.code = 2001`
- `Result.message = 黑名单`

内层 `TradeResult`：

- `code = "1"`
- `msg = RISK级风险文案`
- `level = 2`
- `levelMsg = 禁止:用户在黑名单内`

### 21.3 正常进入规则引擎

外层：

- `Result.code = 0`
- `Result.message = SUCCESS`

内层 `TradeResult`：

- 由规则引擎返回
- 可能是默认空结果，也可能是预警/禁止/预警提示结果

### 21.4 方法内部抛异常

外层仍然返回：

- `Result.code = 0`
- `Result.message = SUCCESS`
- `Result.data = null`

这意味着：

- 对调用方来说，**内部异常和真正成功都可能表现成外层成功**

---

## 22. `report` 逻辑中的关键业务优先级

当前代码体现出来的优先级顺序是：

1. **基础配置异步补齐**（不影响结果）
2. **用户维度归一化**（决定后续判断基准）
3. **白名单优先**（命中即放行）
4. **黑名单次之**（命中即拦截）
5. **规则引擎兜底**（名单未命中时才执行）
6. **订单与违规记录异步落库**（不阻塞主返回）

---

## 23. 需要特别注意的实现细节/风险点

下面这些不是“推测”，而是从当前代码直接能读出来的真实行为：

### 23.1 `report` 本身几乎没有参数校验

- 不校验 `vo` 是否为空
- 不校验 `orderInfo` 是否为空
- 不校验核心字段是否缺失

### 23.2 异常被吞掉后，外层仍返回成功

- `catch` 后没有返回错误码
- 而是 `newEmptySuccessResult()`

### 23.3 白名单优先于黑名单

- 同时命中时，最终结果是白名单放行

### 23.4 白/黑名单是“精确值匹配”

- 不是模糊匹配
- 不做标准化
- 依赖 `factorKey` 精确命中

### 23.5 名单的命中字段由 `filterParams` 二次限制

- 即使 `factorKey` 一样
- 没有包含当前字段标记也不算命中

### 23.6 `processOrderInfo` 的超时设计与实际效果不一致

- 注释希望“超时快速返回”
- 实际代码仍然 `responseFuture.get()` 等待真实任务完成

### 23.7 规则统计默认排除 `state=2` 的历史订单

- 即排除已经风控禁止的订单

### 23.8 `预警(1)` 不会把订单状态标成风险态

- 只会保持 `CREATE(1)`
- 真正改状态的是 `禁止(2)` 和 `预警提示(3)`

### 23.9 订单更新分支可能更新不到记录

- 因为 `saveOrUpdate` 更新时没有回填 `id`
- 但 MyBatis 的更新条件又是 `where id = #{id}`

### 23.10 订单保存、违规策略保存、违规模型保存、告警发送，基本都是异步的

- 返回成功不代表这些异步动作已经全部完成

---

## 24. 一句话总结

`report` 的本质是一个“**先做用户维度归一化 → 再走白/黑名单短路判断 → 最后走按合作方和业务维度匹配的多规则并发风控引擎 → 异步落订单和违规记录**”的交易风控上报入口；其中最值得注意的实际行为是：**白名单优先级高于黑名单、异常默认外层成功、预警不改订单状态、以及订单更新分支可能因缺少 `id` 而无法真正更新。**

---

## 25. `report` 链路里各个交易规则处理器的逻辑明细

下面这一节只覆盖 `report -> processOrderInfo -> TradeRuleManager` 这条交易风控链路里会被调用到的**交易规则处理器**，不包含用户因子侧的 `UserFactorParser`。

先给一个总规则：

- 大部分处理器都继承 `AbstractTradeStrategyParser`
- 大部分“次数类”策略最终调用 `processorCount()`
- 大部分“累计金额类”策略最终调用 `processorAmount()`
- 大部分“金额门槛 + 次数”或“金额门槛 + 累计金额”类策略，都会把 `PM` 放入查询条件，即只统计 `trade_money >= PM` 的历史订单
- 大部分策略都会把**当前订单也算进最终判断**：
  - 次数类通常通过 `count >= N` 或者把当前订单手工加进去
  - 金额类通过 `历史金额 + 当前订单金额 >= AM`

### 25.1 四个最基础的共性模板

#### A. 产品线次数模板：`partnerCountParser`

- 处理器：`PartnerCountParserTrade`
- 作用：统计**同一产品线 + 同一接口 + 同一交易类型**在时间窗口 `T` 内的订单次数
- 查询条件：`partnerId + serviceId + tradeType + state!=2 + T`
- 命中规则：`count >= N`
- 当前订单是否计入：**不直接手工加一**，取决于历史数据里是否已存在；该模板本身只统计数据库中的订单数
- 返回文案参数：`T`、`N`

#### B. 产品线累计金额模板：`partnerAmountParser`

- 处理器：`PartnerAmountParserTrade`
- 作用：统计**同一产品线 + 同一接口 + 同一交易类型**在时间窗口 `T` 内的累计交易金额
- 查询条件：`partnerId + serviceId + tradeType + state!=2 + T`
- 预检查：
  - 先检查时间窗口是否合法
  - 再做 `preCheckMoney`：如果**当前订单金额本身已经大于配置总额阈值 `AM`**，直接判违规
- 命中规则：`历史累计金额 + 当前订单金额 >= AM`
- 返回文案参数：`T`、`AM`

#### C. 产品线金额门槛次数模板：`partnerMoneyCountParser`

- 处理器：`PartnerMoneyCountParserTrade`
- 作用：统计**同一产品线 + 同一接口 + 同一交易类型**下，时间窗口 `T` 内**单笔金额不低于 `PM`** 的订单次数
- 查询条件：`partnerId + serviceId + tradeType + state!=2 + T + tradeMoney>=PM`
- 命中规则：`count >= N`
- 返回文案参数：`T`、`PM`、`N`

#### D. 产品线金额门槛累计金额模板：`partnerMoneyAmountParser`

- 处理器：`PartnerMoneyAmountParserTrade`
- 作用：统计**同一产品线 + 同一接口 + 同一交易类型**下，时间窗口 `T` 内**单笔金额不低于 `PM`** 的订单累计金额
- 查询条件：`partnerId + serviceId + tradeType + state!=2 + T + tradeMoney>=PM`
- 预检查：
  - 先校验时间窗口
  - 再做 `preCheckMoney`
- 命中规则：`历史累计金额 + 当前订单金额 >= AM`
- 返回文案参数：`T`、`PM`、`AM`

### 25.2 用户维度模板族：按 `userType + userName` 做次数/金额统计

这一组处理器的共同特点是：

- 都围绕 `userName + userType` 做统计
- 查询范围固定为：`partnerId + serviceId + tradeType + state!=2 + userName + userType + T`
- 只有当 `orderInfo.userType == 处理器要求的 userType` 时才真正执行
- 命中后默认会保存 `breach_strategy_item`

#### 25.2.1 用户次数：`userCountParser` 及其子类

- 基类处理器：`UserCountParserTrade`
- 作用：统计同一用户在同一产品线、同一接口、同一交易类型下，时间窗口 `T` 内的下单次数
- 命中规则：`count >= N`

子类只是在“用户类型”上做区分：

- `passportCountParser`：通行证用户
- `weChatCountParser`：微信账号用户
- `alipayCountParser`：支付宝账号用户
- `qqCountParser`：QQ 账号用户
- `bankCountParser`：银行卡用户
- `idCardCountParser`：身份证账号用户

#### 25.2.2 用户累计金额：`userAmountParser` 及其子类

- 基类处理器：`UserAmountParserTrade`
- 作用：统计同一用户在同一产品线、同一接口、同一交易类型下，时间窗口 `T` 内的累计金额
- 预检查：
  - 用户类型不匹配直接跳过
  - 时间窗口非法直接跳过
  - 当前单金额超过 `AM` 时直接判违规
- 命中规则：`历史累计金额 + 当前订单金额 >= AM`

子类用户类型映射：

- `passportAmountParser`
- `weChatAmountParser`
- `alipayAmountParser`
- `qqAmountParser`
- `bankAmountParser`
- `idCardAmountParser`

#### 25.2.3 用户金额门槛次数：`userMoneyCountParser` 及其子类

- 基类处理器：`UserMoneyCountParserTrade`
- 作用：统计同一用户在同一产品线、同一接口、同一交易类型下，时间窗口 `T` 内**单笔金额 >= PM** 的订单次数
- 查询条件额外增加：`tradeMoney >= PM`
- 命中规则：`count >= N`

子类用户类型映射：

- `passportMoneyCountParser`
- `weChatMoneyCountParser`
- `alipayMoneyCountParser`
- `qqMoneyCountParser`
- `bankMoneyCountParser`
- `idCardMoneyCountParser`

#### 25.2.4 用户金额门槛累计金额：`userMoneyAmountParser` 及其子类

- 基类处理器：`UserMoneyAmountParserTrade`
- 作用：统计同一用户在同一产品线、同一接口、同一交易类型下，时间窗口 `T` 内**单笔金额 >= PM** 的订单累计金额
- 查询条件额外增加：`tradeMoney >= PM`
- 预检查：
  - 用户类型不匹配直接跳过
  - 时间窗口非法直接跳过
  - 当前单金额大于 `AM` 时直接判违规
- 命中规则：`历史累计金额 + 当前订单金额 >= AM`

子类用户类型映射：

- `passportMoneyAmountParser`
- `weChatMoneyAmountParser`
- `alipayMoneyAmountParser`
- `qqMoneyAmountParser`
- `bankMoneyAmountParser`
- `idCardMoneyAmountParser`

### 25.3 同一下单人对应的不同付款人数：`userNameCountParser` 族

#### A. 基础版：`userNameCountParser`

- 处理器：`UserNameCountParserTrade`
- 作用：统计**同一个 `passportName`** 在时间窗口 `T` 内，关联了多少个不同的 `userName`
- 查询条件：`partnerId + serviceId + passportName + T`
- 实现方式：
  - 先查历史 `distinct user_name`
  - 再把当前订单的 `userName` 手工 `add` 进去
  - 然后 `distinct().count()`
- 命中规则：`不同 userName 数 >= N`
- 特点：这里统计的不是订单次数，而是“不同支付标识”的个数

#### B. 微信版：`weChatUserNameCountParser`

- 在基础版上增加了 `USER_TYPE` 查询条件
- 并且只有 `orderInfo.userType == WEIXIN_ACCOUNT` 时才执行
- 作用：统计同一 `passportName` 在时间窗口内关联的**不同微信账号数**

#### C. 支付宝版：`alipayUserNameCountParser`

- 在基础版上增加了 `USER_TYPE` 查询条件
- 并且只有 `orderInfo.userType == ALIPAY_ACCOUNT` 时才执行
- 作用：统计同一 `passportName` 在时间窗口内关联的**不同支付宝账号数**

### 25.4 身份证维度处理器：围绕 `idCardNo` 统计

#### A. `idCardNoCountParser`

- 处理器：`IdCardNoCountParserTrade`
- 作用：统计**同身份证号**在同一产品线、同一接口、同一交易类型下，时间窗口 `T` 内的订单次数
- 查询条件：`partnerId + serviceId + tradeType + state!=2 + idCardNo + T`
- 命中规则：`count >= N`

#### B. `idCardNoAmountParser`

- 处理器：`IdCardAmountParserTrade`
- 作用：统计**同身份证号**的累计金额
- 查询条件：`partnerId + serviceId + tradeType + state!=2 + idCardNo + T`
- 预检查：时间窗口校验 + `preCheckMoney`
- 命中规则：`历史累计金额 + 当前订单金额 >= AM`

#### C. `idCardNoMoneyCountParser`

- 处理器：`IdCardNoMoneyCountParserTrade`
- 作用：统计**同身份证号**下，时间窗口 `T` 内**单笔金额 >= PM** 的订单次数
- 查询条件：`partnerId + serviceId + tradeType + state!=2 + idCardNo + T + tradeMoney>=PM`
- 命中规则：`count >= N`

#### D. `idCardNoMoneyAmountParser`

- 处理器：`IdCardNoMoneyAmountParserTrade`
- 作用：统计**同身份证号**下，时间窗口 `T` 内**单笔金额 >= PM** 的累计金额
- 查询条件：`partnerId + serviceId + tradeType + state!=2 + idCardNo + T + tradeMoney>=PM`
- 预检查：时间窗口校验 + `preCheckMoney`
- 命中规则：`历史累计金额 + 当前订单金额 >= AM`

### 25.5 支付地区分散度处理器：`payAreaCountParser` 族

这一组不是统计次数，而是统计“不同地区数量”。

共同特点：

- 查询条件：`partnerId + serviceId + passportName + T`
- 如果当前订单 `ip` 为空，直接不检测
- 当前订单的 `cityKey` 如果没填，会先通过 `ip -> cityKey` 补齐
- 只处理 `cityKey >= 100000` 的有效地区编码
- 会把**当前订单对应地区也加进去**再统计
- 命中判断用的是：`count > N`，注意这里是**严格大于**，不是 `>=`

#### A. `payAreaCountParser`

- 处理器：`PayAreaCountParserTrade`
- 作用：统计同一 `passportName` 在同一产品线、同一接口、时间窗口 `T` 内的**不同地区（区县级 area）数量**
- 去重逻辑：直接对 `cityKey` 去重

#### B. `payCityCountParser`

- 处理器：`PayCityCountParserTrade`
- 作用：统计**不同城市数量**
- 去重逻辑：使用 `cityKey / 100` 归并到城市级别后再去重

#### C. `payProvinceCountParser`

- 处理器：`PayProvinceCountParserTrade`
- 作用：统计**不同省份数量**
- 去重逻辑：使用 `cityKey / 10000` 归并到省份级别后再去重

### 25.6 登录地与支付地不一致处理器：`loginAndPay...` 族

这一组不是统计次数，也不是统计金额，而是做**登录城市/支付城市是否一致**的判断。

共同特点：

- 核心数据源：`userFactorInfoService.getCityKeyListByMemberName(orderInfo.getPassportName())`
- 含义：取该下单人的历史登录地区列表
- 如果当前订单没有 `cityKey`，会通过当前 `ip` 反查得到
- 如果：
  - 历史登录地区为空，或
  - 当前 `cityKey` 为空，或
  - 当前 `cityKey < 100000`
  则直接不判违规
- 命中条件：**当前支付地区不在历史登录地区集合内**
- 这一组没有 `N / AM / PM` 阈值比较，属于布尔型差异检测

#### A. `loginAndPayAreaParser`

- 作用：按**区县级 area** 比较当前支付地和历史登录地是否一致
- 一致判断：`历史列表 contains 当前 cityKey`

#### B. `loginAndPayCityParser`

- 作用：按**城市级**比较
- 一致判断：`历史 cityKey / 100 == 当前 cityKey / 100`

#### C. `loginAndPayProvinceParser`

- 作用：按**省份级**比较
- 一致判断：`历史 cityKey / 10000 == 当前 cityKey / 10000`

#### D. 商品限定版：`loginAndPayItemAreaParser / loginAndPayItemCityParser / loginAndPayItemProvinceParser`

- 这三者分别是上面 Area / City / Province 的“商品限定版”
- 额外前置条件：`orderInfoService.getOrderPrefix(orderInfo)` 必须非空
- 含义：只有当当前订单能识别出配置过的**商品前缀**时，才会继续检测“登录地与支付地是否不一致”

### 25.7 商品维度处理器：`payItem...` 族

这组策略的核心概念是：

- 先通过 `orderInfoService.getOrderPrefix(orderInfo)` 识别订单所属商品前缀
- 然后只统计**同商品前缀**下的历史订单

共同前置条件：

- 时间窗口校验通过
- 部分处理器要求用户类型匹配
- `orderPrefix` 必须能识别出来，否则直接跳过
- `ip` 不能为空，否则跳过
- `cityKey` 无值时会尝试通过 `ip` 反查
- `cityKey < 100000` 时不继续执行

#### A. `payItemCountParser`

- 处理器：`PayItemCountParserTrade`
- 作用：统计**同商品前缀 + 同下单人**在时间窗口内的订单数
- 查询条件：`partnerId + serviceId + passportName + T`
- 统计逻辑：
  - 从历史订单中过滤出 `orderNo` 以同一 `orderPrefix` 开头的订单
  - 再把当前订单加入集合
  - 最终返回数量
- 命中规则：`count > N`
- 注意：这里是**大于**，不是 `>=`

#### B. `payItemAreaCountParser`

- 作用：统计**同商品前缀 + 同下单人**对应的**不同地区数量**
- 统计逻辑：对命中的订单取 `cityKey`，直接按地区去重
- 不校验用户类型（`checkUserType=false`）
- 命中规则：`不同地区数 > N`

#### C. `payItemCityCountParser`

- 作用：统计**不同城市数量**
- 去重逻辑：`cityKey / 100`
- 命中规则：`不同城市数 > N`

#### D. `payItemProvinceCountParser`

- 作用：统计**不同省份数量**
- 去重逻辑：`cityKey / 10000`
- 命中规则：`不同省份数 > N`

#### E. `payItemUserNameCountParser`

- 作用：统计**同商品前缀 + 同下单人**对应的**不同付款账号数**
- 不校验用户类型
- 统计逻辑：
  - 历史订单中只保留同商品前缀的数据
  - 取其中不同的 `userName`
  - 再把当前订单 `userName` 加入集合
- 命中规则：`不同 userName 数 > N`

#### F. `weChatItemUserNameCountParser`

- 作用：统计**同商品前缀 + 同下单人**对应的**不同微信账号数**
- 额外查询条件：追加 `USER_TYPE`
- 前置限制：当前订单 `userType` 必须是微信
- 统计逻辑：
  - 只保留同商品前缀订单
  - 对 `userName` 去重
  - 再把当前订单加入
- 命中规则：`不同微信 userName 数 > N`

#### G. `alipayItemUserNameCountParser`

- 作用：统计**同商品前缀 + 同下单人**对应的**不同支付宝账号数**
- 额外查询条件：追加 `USER_TYPE`
- 前置限制：当前订单 `userType` 必须是支付宝
- 命中规则：`不同支付宝 userName 数 > N`

### 25.8 收款人维度处理器：`receiveUser...` 族

这一组全部围绕 `receivePassportName` 做“金额门槛次数”统计，即：

- 先按收款人聚合
- 只统计 `trade_money >= PM` 的历史订单
- 最终比较 `count >= N`

#### A. `receiveUserMoneyCountParser`

- 处理器：`ReceiveUserMoneyCountParserTrade`
- 查询条件：`partnerId + serviceId + tradeType + state!=2 + receivePassportName + T + tradeMoney>=PM`
- 作用：统计**同收款人 + 同产品线 + 同接口**下，大额订单次数

#### B. `receiveUserNoPMoneyCountParser`

- 处理器：`ReceiveUserNoPMoneyCountParserTrade`
- 查询条件：`serviceId + tradeType + state!=2 + receivePassportName + T + tradeMoney>=PM`
- 作用：统计**同收款人 + 同接口**下的大额订单次数
- 特点：去掉了 `partnerId` 维度

#### C. `receiveUserNoPSMoneyCountParser`

- 处理器：`ReceiveUserNoPSMoneyCountParserTrade`
- 查询条件：`tradeType + state!=2 + receivePassportName + T + tradeMoney>=PM`
- 作用：统计**同收款人**在所有产品线、所有接口下的大额订单次数
- 特点：同时去掉了 `partnerId` 和 `serviceId`

#### D. `receiveUserNoSMoneyCountParser`

- 处理器：`ReceiveUserNoSMoneyCountParserTrade`
- 查询条件：`partnerId + tradeType + state!=2 + receivePassportName + T + tradeMoney>=PM`
- 作用：统计**同收款人 + 同产品线**下的大额订单次数
- 特点：保留 `partnerId`，去掉 `serviceId`

---

## 26. 各处理器的关系图谱（便于快速定位）

为了后续你继续跟源码时更快，这里把继承关系和职责再压缩成一份“速查表”。

### 26.1 直接统计次数

- 产品线次数：`partnerCountParser`
- 用户次数：`userCountParser / passportCountParser / weChatCountParser / alipayCountParser / qqCountParser / bankCountParser / idCardCountParser`
- 身份证次数：`idCardNoCountParser`
- 金额门槛次数：
  - 产品线：`partnerMoneyCountParser`
  - 用户：`userMoneyCountParser` 及各用户类型子类
  - 身份证：`idCardNoMoneyCountParser`
  - 收款人：`receiveUser...MoneyCountParser` 系列

### 26.2 直接统计累计金额

- 产品线累计金额：`partnerAmountParser`
- 用户累计金额：`userAmountParser` 及各用户类型子类
- 身份证累计金额：`idCardNoAmountParser`
- 金额门槛累计金额：
  - 产品线：`partnerMoneyAmountParser`
  - 用户：`userMoneyAmountParser` 及各用户类型子类
  - 身份证：`idCardNoMoneyAmountParser`

### 26.3 统计不同主体数量

- 同下单人不同付款人：`userNameCountParser / weChatUserNameCountParser / alipayUserNameCountParser`
- 商品维度不同付款人：`payItemUserNameCountParser / weChatItemUserNameCountParser / alipayItemUserNameCountParser`
- 地域分散度：`payAreaCountParser / payCityCountParser / payProvinceCountParser`
- 商品维度地域分散度：`payItemAreaCountParser / payItemCityCountParser / payItemProvinceCountParser`

### 26.4 差异检测类

- 登录地 vs 支付地不一致：
  - `loginAndPayAreaParser`
  - `loginAndPayCityParser`
  - `loginAndPayProvinceParser`
  - `loginAndPayItemAreaParser`
  - `loginAndPayItemCityParser`
  - `loginAndPayItemProvinceParser`
