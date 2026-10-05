# MultiLogin DHW PCS 分支技术说明

本文记录 DHW PCS 临时维护分支的目标版本、构建方法和验证结果。仓库性质、使用限制和迁移建议见 [README.md](README.md)。

> [!WARNING]
> 本分支尚未发布经过验证的正式 fork release。源码构建及 GitHub Actions artifact 不应被视为面向生产环境的通用发行版。

## 目标与构建基线

- 当前版本：`0.6.14-dhw`
- 当前 Velocity 目标及构建基线：`3.5.1` build `615`
- Java 基线：JDK 25
- 正式版本格式：`<major>.<minor>.<patch>-dhw`
- 维护范围：DHW Inf 迁移期间所需的有限兼容性维护

构建会从 PaperMC 官方下载目标服务器 JAR，并使用固定的 SHA-256 `b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3` 校验。Velocity `3.5.1` 的稳定构建可在 [PaperMC 构建元数据](https://fill.papermc.io/v3/projects/velocity/versions/3.5.1/builds) 中确认。本分支不滚动跟踪 Velocity 快照或 Velocity 4.x。

## 功能范围

MultiLogin 允许同一个 Velocity 代理接受官方 Minecraft 认证、多个 Yggdrasil 服务以及 Floodgate 等身份来源。现有代码还包括：

- 多认证源选择、代理与重试流程
- 游戏内档案与账号关联管理
- 异步及同步皮肤恢复
- 白名单、查找和资料管理命令
- H2 与 MySQL 数据存储

这些是现有代码能力的说明，不构成对本分支在其他服务器环境中的支持承诺。

## Velocity 3.5.1 本机验证

本分支在以下隔离拓扑上完成了主要运行验证：

| 组件 | 已测试版本或设置 |
| --- | --- |
| Java | Eclipse Temurin `25.0.3+9` |
| 代理 | Velocity `3.5.1` build `615` |
| 客户端与后端 | Minecraft `26.2` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.155.2+26.2` |
| FabricProxy-Lite | `2.12.0` |
| 转发模式 | Velocity Modern Forwarding |
| 认证来源 | Mojang Official、DHW PCS Passport Yggdrasil |

[FabricProxy-Lite 2.12.0](https://modrinth.com/mod/fabricproxy-lite/version/v2.12.0) 的测试文件 SHA-256 为 `dca0d05685afaa25d554372ad118d90b6b27f85ded93e6db0b85d822aa29342a`。

本次测试覆盖：

- 代理启动和正常关闭
- Mojang Official 与 DHW PCS Passport 认证
- UUID/profile 与 Modern Forwarding
- 进入 Minecraft 26.2 Fabric 后端
- 聊天及 Minecraft 26.2 ChatSession 包
- 认证拒绝与认证期间断开
- 重复登录冲突
- 正常退出和重连

Minecraft 26.2 实测 ChatSession 包 ID 为 `0x0A`。由于 MultiLogin 会拦截 ChatSession 公钥包，测试后端使用 `enforce-secure-profile=false`；否则后端会拒绝未签名聊天。

本次拓扑没有安装 Floodgate，因此 Floodgate 未经过本次运行验证。

### Minecraft 1.21.11 后端兼容性复测

由于 DHW Inf 服务器仍运行 Minecraft `1.21.11`，本分支另以相同的 Velocity `3.5.1` build `615`、JDK `25.0.3+9` 和 Modern Forwarding 配置完成了一次针对该版本的真实客户端复测：

| 组件 | 已测试版本或设置 |
| --- | --- |
| 客户端与后端 | Minecraft `1.21.11` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.141.5+1.21.11` |
| FabricProxy-Lite | `2.11.0` |
| 认证来源 | Mojang Official |

测试使用的 Fabric 服务端启动 JAR SHA-256 为 `ed1b811d75555bd18dfc2b15199d94559ee2aab035e3c92ee43d3c4addca819f`；[Fabric API 0.141.5+1.21.11](https://modrinth.com/mod/fabric-api/version/zGF3drOQ) 的 SHA-256 为 `2525f9b7bb5524d409404831f7af276a86f7f1e90245ae4bb7555fe088d00e0d`；[FabricProxy-Lite 2.11.0](https://modrinth.com/mod/fabricproxy-lite/version/nR8AIdvx) 的 SHA-256 为 `ebc7abeaf6c03ac619c701ebb332de6966c6d83edfabf02434720c8fc3d03cdc`。

真实 Minecraft `1.21.11` 客户端使用 Mojang Official 账号完成了两次认证、UUID/profile 转发、进入 Fabric 后端、发送聊天消息、正常退出及重新连接。代理与后端日志没有出现认证、ChatSession、转发或清理异常。此复测未重复执行 DHW PCS Passport、认证拒绝、冲突登录或认证期间断开测试；这些路径的运行验证来自上述 Minecraft `26.2` 拓扑。两个拓扑均未测试 Floodgate。

### ChatSession 映射修复（2026-10-06）

原实现按包 ID 去重，导致旧配置中的 `775: 0x0A` 删除内置的 `776: 0x0A`，自动发现也无法恢复该映射，玩家因而重复收到重连提示。

- `mapper.yml` 格式保持不变；不同协议现在可以保留相同包 ID。加载时以内置映射为基础，磁盘条目只覆盖同一协议；因此旧 775 条目不会删除 776 默认值，无需手动迁移配置。
- 配置重载会读取磁盘内容，不再先保存旧内存内容；保存会清除已删除的 mapper 条目、保留其他配置节点，并通过同目录临时文件原子替换原文件。文件系统不支持原子替换或保存失败时，原文件保留，更新失败会报告错误。
- 注册继续限制继承范围，最后一项仅覆盖其起始协议，遇到其他包的 ID 冲突即停止继承；未知协议不会注册。整批注册先检查冲突，失败时只恢复本次修改的条目。
- 自动发现会核验实际解码器并保存配置，全部成功后才向原连接发送一次重连提示；相同已安装映射不再要求重连。保存或注册失败会恢复映射和本次注册修改，并提示玩家联系管理员，日志仅记录协议、包 ID、前后映射和失败阶段。
- 已完整识别的 ChatSession 数据包会被吞掉并释放，每条连接只触发一次发现任务。原连接断开后不会按 UUID 踢出后来建立的新连接。
- 手工修改或删除已注册的映射后，需要重启 Velocity，使运行时解码器与配置一致；重载不会卸载已注册的解码器，也不会在线更换 blocker 的包 ID。回退插件前应恢复备份的 mapper；旧插件用于 26.2 时，应在停机后将 `775: 0x0A` 替换为 `776: 0x0A`，避免旧去重逻辑丢失 26.2 映射。

本次使用 Eclipse Temurin `25.0.4.1+1-LTS`、Gradle `9.0.0` 和 Velocity `3.5.1` build `615` 完成了 30 项自动测试、`clean test shadowJar` 与 `test shadowJar`；第二轮的 `downloadVelocityBaseline` 为 up to date。开发 JAR 的插件元数据、内嵌 Core/Injector、配置资源、manifest 及固定 Velocity 校验值均已检查。隔离 26.2 代理和后端完成了启动与正常关闭；以下为修复后的本次验收结果，以上历史拓扑记录不代替这些结果。尚未发布正式 release，也未运行远程 CI。

本次隔离运行继续使用 Fabric Loader `0.19.3`、Fabric API `0.155.2+26.2`、FabricProxy-Lite `2.12.0`、Modern Forwarding、绑定 loopback 的后端及 `enforce-secure-profile=false`。客户端版本由测试者确认为 `26.2`；测试者关闭 NCR 后，临时探针实际观察到协议 776 的 ChatSession 包。开发构件为基于 `903f02a` 的未提交工作树构建，未发布为正式版本。

| 本次验收项 | 结果 |
| --- | --- |
| 旧 mapper 仅有 `775: 0x0A` | 内置 776 映射保留；两种认证均正常进入 26.2 后端 |
| Mojang Official | 两次成功认证、稳定 UUID/profile、聊天、正常退出和重连 |
| DHW PCS Passport | 多次成功认证、稳定 UUID/profile、聊天和重连；关闭 NCR 后观察到 ChatSession 被正常拦截 |
| Passport 受控自动发现 | 临时探针仅移除 776 的 blocker；首次连接安装并保存映射后只提示一次重连，后续三次进入后端、聊天和退出均正常 |
| 持久化与启动 | 保存文件同时保留 775、776 的 `0x0A`；再次启动能安装 776 解码器 |
| 认证失败与中途断开 | 合成客户端验证加密挑战后断开、加密响应后认证期间断开、未加入认证会话被拒绝；未观察到清理异常 |
| NCR 开关对照 | 先前连接未观察到 ChatSession；测试者关闭 NCR 后观察到对应包，不据此断言生产事故由某个模组导致 |
| 未完成项 | 官方认证受控自动发现、真实冲突登录及 Floodgate；用户停止追加客户端测试，并将 1.21.11 复测排除本次范围 |

早期隔离配置先尝试未启动的 1.21.11 后端，产生连接拒绝后回退至 26.2；随后已将测试默认后端改为 26.2。运行日志、合成 mapper 和临时探针留在忽略的 `.local-test/`；测试结束后代理与后端正常关闭，探针已移除，原测试配置与 mapper 已恢复，保留修复后的开发 JAR。上述结果不等同于完整认证矩阵或生产验证。

## 开发环境

需要 Git、JDK 25，以及能够访问 Gradle、Maven Central、PaperMC 仓库和 PaperMC 下载服务的网络。

macOS 可使用 Homebrew 安装 Eclipse Temurin 25：

```shell
brew install --cask temurin@25
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
```

确认工具链：

```shell
java -version
javac -version
./gradlew --version
```

三个命令都应显示 Java 25。

## 构建

开发构件的完整验证命令：

```shell
./gradlew clean test shadowJar
./gradlew test shadowJar
```

主要开发构件位于：

```text
velocity/build/libs/MultiLogin-Velocity-Build_<commit>.jar
```

正式版本构建命令：

```shell
./gradlew clean test shadowJar -Denv=final
```

正式构件位于：

```text
velocity/build/libs/MultiLogin-Velocity-<major>.<minor>.<patch>-dhw.jar
```

构建会拒绝缺少 `-dhw` 后缀的正式版本号。仓库只保留手动触发的正式构建 workflow；它仅允许从默认分支发布，在运行完整测试后同时上传 Actions artifact，并以 `v<major>.<minor>.<patch>-dhw` tag 创建 GitHub Release、生成发行说明及上传正式 JAR。已有同名 tag 或 Release 时 workflow 会停止，不覆盖已经发布的构件。

首次构建可能生成 Gradle 缓存、各模块的 `build/` 目录、校验后的 Velocity 目标 JAR 和 `.digests`。这些文件均不应提交。目标 JAR 的 SHA-256 不匹配时，构建会立即失败。

## 构件检查

正式交付前至少检查：

- `velocity-plugin.json` 中的 ID、入口点和版本
- 内嵌 `MultiLogin-Core.JarFile` 与 `MultiLogin-Velocity-Injector.JarFile`
- Core 内的 `config.yml`、`mapper.yml` 与 `message.properties`
- manifest 中的 Git revision、Gradle 和 JDK
- 固定 Velocity JAR 的 SHA-256
- `git diff --check`

## 配置资料

配置模板位于 `core/src/main/resources/`。上游 [MultiLogin Wiki](https://github.com/CaaMoe/MultiLogin/wiki) 仅可作为历史参考；其中的安装步骤、版本要求和配置说明可能已经过时，应以本仓库代码和经过验证的 DHW Inf 配置为准。
