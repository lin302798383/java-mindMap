// 伪代码
while (!stopped) {
    PullRequest request = pullRequestQueue.take(); // 阻塞等待
    pullMessage(request);
}
```

#### PullRequest 结构
```
PullRequest
  ├── consumerGroup      // 消费组
  ├── messageQueue       // 目标队列 (Topic + BrokerName + QueueId)
  ├── processQueue       // 本地镜像
  └── nextOffset         // 下次拉取的起始位置
```

---

### 五、流控检查（拉取前）

取出 PullRequest 后，**先做流控判断**，不通过则延迟 50ms 后重新入队：
```
┌─────────────────────────────────────────────────────┐
│                    流控检查                          │
├──────────────┬──────────────────────────────────────┤
│  检查项       │  阈值 / 说明                          │
├──────────────┼──────────────────────────────────────┤
│ 缓存消息条数  │ ProcessQueue.msgCount > 1000 条       │
├──────────────┼──────────────────────────────────────┤
│ 缓存消息大小  │ ProcessQueue.msgSize > 100 MB         │
├──────────────┼──────────────────────────────────────┤
│ offset 跨度  │ 并发模式下：                           │
│              │ max(offset) - min(offset) > 2000      │
│              │ 说明有消息长期未被消费（消费慢/卡住）   │
├──────────────┼──────────────────────────────────────┤
│ 队列是否废弃  │ processQueue.dropped == true          │
│              │ 直接丢弃，不再拉取                     │
└──────────────┴──────────────────────────────────────┘

任一触发 → 延迟 50ms 将 PullRequest 重新放回队列
```

> offset 跨度检查只在**并发消费**模式下生效；
> 顺序消费模式不检查跨度，因为本身就是串行的。

---

### 六、向 Broker 发起拉取请求

流控通过后，构建请求发往 Broker：
```
请求参数：
├── Topic
├── QueueId
├── QueueOffset       // 从哪个 offset 开始拉
├── MaxMsgNums        // 最多拉多少条（默认 32）
├── SubExpression     // 过滤表达式（Tag / SQL92）
├── CommitOffset      // 当前消费进度（顺带上报）
└── SuspendTimeoutMs  // 长轮询等待时间（默认 15s）
```

---

### 七、Broker 处理拉取请求
```
收到请求
  ↓
权限校验 & 参数校验
  ↓
根据 Topic + QueueId 找到 ConsumeQueue 文件
  ↓
从 QueueOffset 位置读取索引条目
  (每条 20字节: CommitLog偏移 + 消息大小 + Tag哈希)
  ↓
Tag 过滤（哈希值匹配，粗过滤）
  ↓
去 CommitLog 读取完整消息体
  ↓
SQL92 过滤（精确过滤，在完整消息上执行）
  ↓
┌───────────────┬──────────────────────────────┐
│  有消息        │  立即返回消息列表              │
├───────────────┼──────────────────────────────┤
│  无消息        │  长轮询挂起请求（最长 15s）    │
│               │  有新消息写入 → 立即唤醒返回   │
│               │  超时 → 返回空，Consumer重试   │
└───────────────┴──────────────────────────────┘
```

#### 长轮询唤醒机制
```
新消息写入 CommitLog
      ↓
ReputMessageService 异步构建 ConsumeQueue
      ↓
通知 PullRequestHoldService
      ↓
遍历挂起的请求，找到匹配 Topic+QueueId 的
      ↓
立即执行拉取并返回给 Consumer
```

---

### 八、Consumer 处理响应
```
收到 Broker 响应
      ↓
根据 PullStatus 处理：

FOUND（有消息）
  ├── 将消息存入 ProcessQueue.msgTreeMap
  ├── 更新 nextOffset
  ├── 提交消息到 ConsumeMessageService 线程池
  └── 立即将 PullRequest 重新放入 pullRequestQueue

NO_NEW_MSG / NO_MATCHED_MSG（无消息）
  ├── 更新 nextOffset
  └── 延迟 3s 后将 PullRequest 重新放入队列

OFFSET_ILLEGAL（offset 非法）
  ├── 修正 offset（取最大或最小合法值）
  └── 将 ProcessQueue 标记 dropped，重新触发 Rebalance
```

---

### 九、消息消费与 offset 提交
```
ConsumeMessageService 线程池
      ↓
执行用户注册的 MessageListener
      ↓
┌─────────────────┬──────────────────────────────┐
│ CONSUME_SUCCESS │ 从 ProcessQueue.msgTreeMap    │
│                 │ 移除该消息                    │
│                 │ 更新本地 offsetStore           │
├─────────────────┼──────────────────────────────┤
│ RECONSUME_LATER │ 发回 Broker 延迟重试队列       │
│                 │ 也从 msgTreeMap 移除           │
└─────────────────┴──────────────────────────────┘
      ↓
offsetStore 每 5s 持久化一次
（集群模式 → 上报 Broker；广播模式 → 写本地文件）
```

#### offset 提交的关键细节
```
msgTreeMap 是 TreeMap，按 offset 排序

提交的 offset = msgTreeMap 中最小的 offset
（不是消费完的那条，而是还未消费的最小值）

原因：防止消息乱序时，offset 跳跃导致漏消费
```
```
例：消费了 offset=100,101,103，但 102 还在消费中
提交的 offset = 102（最小未完成的）
不会提交 103，避免重启后 102 被跳过
```

---

### 十、完整流程串联
```
RebalanceService（20s周期）
    │
    │  分配队列，生成 PullRequest
    ▼
pullRequestQueue（阻塞队列）
    │
    │  PullMessageService 单线程取出
    ▼
流控检查
    │ 不通过 → 延迟50ms重新入队
    │ 通过
    ▼
发送请求到 Broker
    │
    ├── 有消息 → 立即返回
    └── 无消息 → 长轮询挂起(15s)
                  │
                  ├── 新消息到 → 唤醒立即返回
                  └── 超时 → 返回空
    │
    ▼
Consumer 收到消息
    │
    ├── 存入 ProcessQueue.msgTreeMap
    ├── PullRequest 重新入队（继续拉）
    │
    ▼
ConsumeMessageService 线程池消费
    │
    ├── 成功 → 从 msgTreeMap 移除 → 更新 offset
    └── 失败 → 发回 Broker 延迟重试
    │
    ▼
offsetStore 每5s持久化