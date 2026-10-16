# 1.0.1 快照优化对比

## 结论与边界

用户于 2026-10-16 批准保守优化。保留原有暂停期间建立隔离快照的模型，减少重复元数据系统调用，并使用有界 channel 传输；没有引入实时世界硬链接或跨 tick 复制。

同一批模拟源文件、同一 JVM、独立 class loader 中交替调用 1.0.0 与优化后生产 stager。三轮中分别按旧→新、新→旧、旧→新执行，文件生成不计时，快照删除另计时。所有数据均为工具新建，结束后完整清理。

| 场景 | 旧版 stage 中位数 | 优化版 stage 中位数 | 中位数降幅 |
| --- | --- | --- | --- |
| 16 个文件 / 1 GiB | 942.990 ms | 885.654 ms | 6.1% |
| 8192 个文件 / 128 MiB | 23067.756 ms | 14330.277 ms | 37.9% |
| 2112 个文件 / 544 MiB | 6284.588 ms | 4628.118 ms | 26.4% |

大文件样本范围重叠，不把 6.1% 解释为稳定、可保证的提升。混合场景第二轮新旧相近，其他两轮较快；样本只有三轮，没有给出统计显著性结论。Windows 缓存、后台活动与防病毒扫描未隔离；这些数据不能外推到 HDD、冷缓存或实际游戏帧率。先前分进程探查的绝对值不同，因此主结论采用这里的同数据交替对比。

## 原始结果

| 场景 | 轮次 | 旧 stage ms | 新 stage ms | 旧 cleanup ms | 新 cleanup ms |
| --- | --- | --- | --- | --- | --- |
| 大文件 | 1 | 747.443 | 831.378 | 89.623 | 93.089 |
| 大文件 | 2 | 942.990 | 885.654 | 122.932 | 108.668 |
| 大文件 | 3 | 948.056 | 906.987 | 113.404 | 110.008 |
| 小文件 | 1 | 23067.756 | 14077.495 | 1704.905 | 1694.854 |
| 小文件 | 2 | 22773.266 | 14330.277 | 1902.694 | 1694.470 |
| 小文件 | 3 | 23646.530 | 14611.597 | 2065.332 | 1696.888 |
| 混合 | 1 | 6209.029 | 4127.037 | 540.797 | 503.732 |
| 混合 | 2 | 6284.588 | 6334.881 | 540.899 | 530.707 |
| 混合 | 3 | 6363.565 | 4628.118 | 656.887 | 530.851 |

## 改动与验收

- 以 canonical 源根目录下的绝对规范化子路径为比较对象，保留 followed real path 等值和 containment 检查，省去重复的 NOFOLLOW realpath。复制前后仍检查 source identity、size 和 mtime。
- 目标类型和大小合并为一次 NOFOLLOW BasicFileAttributes 查询。
- 文件复制使用最多 8 MiB 的 channel transfer，每块检查取消；零进展回退有界缓冲复制。保留 CREATE_NEW、NOFOLLOW、精确内容和修改时间。
- 新增真实 NTFS 内部 junction、复制中父目录替换、空文件、9 MiB+17 字节内容、时间戳、已有目标不覆盖、预取消、真实传输首块后取消并清理残留、短传输、零进展回退测试。
- 分阶段记录真实运行日志；prepare/save/flush 与 release/save 需要实际游戏日志来测量，未从模拟数据推算。
- 独立只读审查未发现 Critical 或 Important。补充的传输中取消/回退覆盖随后纳入专项测试。
- 运行专项测试时发现原 maintenance 测试仅等待调度尝试计数、未等待回调入队，存在测试自身时序窗口。改为同一 executor 的 barrier future，保持生产维护生命周期不变。

旧临时目录清理没有提前到 prepare 之前。正常路径该耗时很小，提前清理需要新增 coordinator 阶段、取消和停服所有权；本轮不为此改变经过验证的生命周期。大量异常残留仍可能产生清理停顿，日志的 housekeeping_ms 会明确显示。

## 复现工具

先保留旧版编译类，再编译优化版。保留的旧类只用于比较，不是发布 JAR：

```powershell
# 在 1.0.0 源码编译完成后执行，再切换到优化源码编译。
.\.toolchains\jdk8\jdk8u504-b01\bin\jar.exe cf baseline-classes.jar -C build/classes/java/main .
.\tools\performance\run-comparison.ps1 -BaselineClasses ./baseline-classes.jar
```

本次旧类快照 SHA-256：`7FB8D079FF31C09B07C05C771E0F7EE3B1BFECA0CAC1DD005B8C43EFDCD38B26`，来源为性能分支修改运行时代码前的 1.0.0 编译类。生成数据的随机种子、场景和交替顺序写在工具中。

工具不会读取用户世界。日志输出 `comparison_cleaned=true`，表示本轮临时模拟目录已删除。旧 1.0.0 标签、GitHub Release 和既有发布 ZIP 未修改。

## 最终构建与本地安装

2026-10-16 使用项目 Java 8 完成干净 Gradle `clean test build`：10 个任务执行、包含 reobfJar，17 个测试套件 / 307 项测试 / 0 failure / 0 error / 1 skipped。跳过项是已有恢复器的 Windows 非法文件名条件测试，不是本次快照测试。独立代码审查在补齐真实传输中取消和回退分支后，无剩余 Critical/Important/Minor。

本机首次离线构建缺少 JUnit 缓存及 Access Transformers 的 Gradle 解析记录。JUnit 4.13.2 从 Maven Central 校验下载并恢复缓存；临时 init script 仅把工具解析指向现有 Access Transformers 1.0.5。测试、编译、访问转换及重混淆任务均真实执行，未跳过；临时缓存修复不属于提交或 JAR 内容。

- JAR：`worldbackup-1.0.1.jar`，269821 字节。
- SHA-256：`9C21FA0BF98AC61EF8BCB82248183F5B055BC41FA6041EE23C449AE6840EB340`。
- BETA3 安装件与构建件哈希一致，仅一份生效 JAR。
- 原 1.0.0 安装件改为非 JAR 后缀 `worldbackup-1.0.0.jar.bak-F3DAAC7E`，可用于回退；没有删除用户数据。
- 未启动真实客户端或打开世界，BETA4/BETA5、GitHub 正式发布及 v1.0.0 标签未修改。
