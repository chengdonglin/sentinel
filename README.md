# Sentinel 源码分析

## 核心入口类

- 利用AOP原理，核心入口类 SentinelResourceAspect
- 拦截@SentinelResource注解

## 责任链模式

- slot 组装程slot chain

## 常用slot

- NodeSelectorSlot ： 负责收集资源的路径，并将这些资源的调用路径，以树状结构存储起来，用于根据调用路径来限流降。
- ClusterBuilderSlot： 用于存储资源的统计信息以及调用者信息，例如该资源的 RT, QPS, thread count，Block count，Exception count 等等，这些信息将用作为多维度限流，降级的依据。简单来说，就是用于构建ClusterNode。
- StatisticSlot：用于记录、统计不同纬度的 runtime 指标监控信息。
- ParamFlowSlot： 对应热点流控
- SystemSlot：通过系统的状态，例如 load1 等，来控制总的入口流量。对应系统规则。
- AuthoritySlot：根据配置的黑白名单和调用来源信息，来做黑白名单控制。对应授权规则。
- FlowSlot： 用于根据预设的限流规则以及前面 slot 统计的状态，来进行流量控制。对应流控规则。
- DegradeSlot：通过统计信息以及预设的规则，来做熔断降级。对应降级规则。

Slot 的加载采用Sentinel的SPI加载机制注入



## 流程图



![](C:\Users\DM\AppData\Roaming\marktext\images\2022-01-28-15-59-27-image.png)
