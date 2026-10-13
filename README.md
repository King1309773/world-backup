# World Backup

World Backup 是面向 Minecraft 1.12.2 单人世界的客户端备份模组。它支持全量、智能增量和覆写备份，并在单人世界选择界面提供备份浏览、校验、恢复和删除功能。

## 环境与构建

- Java 8
- Minecraft 1.12.2
- Minecraft Forge 14.23.5.2860

在项目目录中使用项目内的 Java 8 执行：

```powershell
$env:JAVA_HOME = (Resolve-Path '.\.toolchains\jdk8\jdk8u504-b01').Path
$env:Path = "$env:JAVA_HOME\bin;$env:JAVA_HOME\jre\bin;$env:Path"
.\gradlew.bat clean test build --offline --no-daemon --max-workers=1
```

成品位于 `build/libs/worldbackup-1.0.0.jar`。

## 配置

Forge 的 Mod Options 中可修改以下 8 项设置；默认配置适合直接使用：

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关；关闭后手动和自动备份都不会运行 |
| `scheduledEnabled` | `true` | 启用定时备份 |
| `exitEnabled` | `true` | 退出单人世界时备份 |
| `mode` | `INCREMENTAL` | 备份模式：`FULL`、`INCREMENTAL` 或 `OVERWRITE` |
| `intervalMinutes` | `20` | 定时备份间隔，最小 1 分钟 |
| `maxVersions` | `10` | 最多保留在管理界面中的可恢复版本数，最小为 1 |
| `incrementsPerBase` | `5` | 每个基线最多连续创建的增量数，最小为 1 |
| `backupPath` | `backups` | 相对游戏目录或绝对路径；修改后不会搬迁已有备份 |

“创建手动备份”按键默认未绑定。进入“选项 → 控制”，在“世界备份”分类中为它绑定按键。按键只在已加载的单人世界中生效，并受 `enabled` 总开关控制。

## 备份格式与保留策略

默认仓库布局如下：

```text
<游戏目录>/backups/<世界文件夹>/
├── index.json
├── <版本 ID>-full.zip
├── <版本 ID>-delta.zip
└── <版本 ID>-latest.zip
```

每个 ZIP 内有 `manifest.json`，文件内容位于 `data/<世界内相对路径>`。全量基线使用 `-full.zip`，增量使用 `-delta.zip`，覆写模式使用 `-latest.zip`。索引记录触发来源、父版本、基线、快照清单、变更和删除路径。

智能增量会比较文件大小、修改时间和 SHA-256；内容未变的文件不会重复写入增量包。达到 `incrementsPerBase` 后自动创建新的全量基线。Minecraft region 文件（`.mca`）按普通文件处理：只要内容变化，就把整个 `.mca` 写入归档，不进行区块级差分。

`maxVersions` 限制的是 `visible=true` 的可选恢复点数量。为保证仍可见的增量版本可恢复，其依赖的旧基线或中间增量可能以 `visible=false` 留在索引和磁盘中；这些 hidden dependencies 不占可见版本配额，也不会单独显示在管理界面。删除恢复点时会重新计算依赖，只清理由任何保留恢复点都不再需要的归档。

## 管理、恢复与删除

在“选择世界”界面选择一个世界，再点击右上角“备份管理”。管理器始终针对当前选择的世界读取独立仓库，校验索引身份和完整恢复链。损坏、缺失或截断的归档会显示为“无法恢复”，不会作为可恢复版本使用。

恢复只能在世界未加载时进行：客户端世界和集成服务器都必须已关闭。确认恢复后，现有世界会先原子移动为安全副本，再发布重建并校验后的世界；恢复期间会锁定世界选择界面的进入操作。安全副本位于：

```text
<游戏目录>/restore-safety/<世界文件夹>-yyyyMMdd-HHmmss-SSS[-序号]/
```

安全副本可在管理器的“安全副本”页查看和删除。恢复与删除都执行路径、身份、符号链接/重解析点和归档内容校验。删除备份会先安全更新索引，再删除不再被引用的归档；若归档文件删除失败，界面会给出警告，索引仍保持一致。删除安全副本是永久操作，且在开始前会将目标原子隔离到同目录的 `.deleting-*` 名称；失败时可能保留该隔离目录供人工检查。

## 一致性、限制与故障恢复

- 为得到一致快照，模组会先保存并刷新世界，然后在 `<游戏目录>/.worldbackup-staging/` 复制一份临时快照。复制阶段短暂暂停世界保存并会临时占用接近一份世界大小的额外空间；复制完成后尽快恢复世界，压缩和校验在后台继续。
- 单次备份的看门狗超时为 30 分钟。超时或取消会停止工作并尝试恢复保存状态、清理临时快照。
- 索引读写上限为 64 MiB，超限索引会被拒绝。
- Java 8 在 Windows 上没有可用的 `SecureDirectoryStream`。实现使用目录身份、真实路径、重解析点检查和提交前复检缩小竞态窗口，但对能在操作过程中恶意并发替换目录的本地攻击者，仍存在一个无法完全消除的微小 TOCTOU 窗口。
- `*.atomic-rollback`：归档原子发布时为旧目标保留的回滚副本。成功后删除；若发布及回滚均失败，错误会指出需保留和检查的恢复文件。
- `*.rollback`：不支持原子替换时，经过校验的旧归档回滚副本。失败时可能被保留用于恢复。
- `index.json.bak`：索引替换的已校验回滚副本。正常提交或成功回滚后清理；若故障后遗留，应在关闭游戏后先保存副本，再结合日志判断是否恢复为 `index.json`。
- `.<归档名>-*.building`：归档写入、回读校验完成前的 staging 文件；失败时通常自动删除，孤儿文件不在索引中，可在游戏完全关闭并确认没有备份任务后移走或删除。
- `.worldbackup-staging/snapshot-*`：一致性复制目录；启动下一次 staging 时会清理可安全识别的孤儿目录。不要在游戏或备份仍运行时手动删除。
- `restore-safety/.deleting-*`：安全副本删除中途失败后可能留下的隔离目录。它不会作为普通安全副本显示，应先关闭游戏并人工核对内容后处理。

## 验证状态

### Automated verified

- 2026-10-13：使用项目内 Temurin OpenJDK `1.8.0_504`，带 TLS 1.2/IPv4 兼容参数执行 `clean test build --offline --no-daemon --max-workers=1` 成功。
- JUnit：16 个测试套件，273 个测试，0 failure，0 error，1 个平台条件测试 skipped。
- `worldbackup-1.0.0.jar` 已核对包含 `mcmod.info`、`WorldBackup.class`、`zh_cn.lang`、`en_us.lang`、`META-INF/worldbackup_at.cfg`、GUI 与 runtime 类；没有测试 class，也未扫描到邮箱或绝对本机路径。
- 自动化测试覆盖配置快照、定时/退出/手动协调、全量/增量/覆写创建、增量链和基线轮换、visible/hidden 保留依赖、归档校验、截断/损坏不可用、恢复交换与安全副本、删除和路径安全等核心行为。它们不是实际 UI 操作的替代品。

### Manual pending

2026-10-13 的 `runClient` 验证确认项目 JDK 中存在 `jre/bin/awt.dll`。通过仅对本次进程补充 PATH 和 `java.library.path` 后，客户端已越过原先的 AWT native 错误，创建 OpenGL 上下文并识别、启用 `worldbackup 1.0.0`；随后 ForgeGradle/LegacyDev 开发环境在 Forge 自身 `NetworkRegistry.newChannel` 处抛出 `NullPointerException`，未到达主菜单。该失败发生在 FML 构造阶段，不是 World Backup 的初始化或测试失败，但它阻止了 UI 验证。没有创建测试世界，也没有打开或修改任何已有世界。真实 RLCMJ BETA3 实例的主菜单启动未通过可安全复现的独立命令行完成，留待用户手测。

因此以下项目仍为 manual pending，不能标记为通过：手动按键默认未绑定与绑定后备份、成功消息和耗时、1 分钟定时备份、退出开关组合、增量 manifest、新基线节奏、保留 10 个恢复点、恢复安全副本、截断归档不可恢复、全量/覆写模式、世界选择界面的管理入口，以及真实整合包主菜单启动。

验证过程不得打开任何已有世界。若需要客户端验证，只能创建名称明确的临时测试世界（例如 `WB_TEST_<时间戳>`），并在结束后记录它是否已删除；验证结束时配置必须恢复为默认的 20 分钟间隔和 `INCREMENTAL` 模式。
