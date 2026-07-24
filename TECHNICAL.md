# MultiLogin DHW PCS 分支技术说明

本文记录 DHW PCS 临时维护分支的目标版本、构建方法和验证结果。仓库性质、使用限制和迁移建议见
[README.md](README.md)。

> [!WARNING]
> 本分支尚未发布经过验证的正式 fork release。源码构建及 GitHub Actions artifact 不应被视为
> 面向生产环境的通用发行版。

## 目标与构建基线

- 当前版本：`0.6.14-dhw`
- 当前 Velocity 目标及构建基线：`3.5.1` build `615`
- Java 基线：JDK 25
- 正式版本格式：`<major>.<minor>.<patch>-dhw`
- 维护范围：DHW Inf 迁移期间所需的有限兼容性维护

构建会从 PaperMC 官方下载目标服务器 JAR，并使用固定的 SHA-256
`b4e3164df5377346854dc6cb9e6a78022b1946ff69e89676313f5f6f1c6f0fb3` 校验。Velocity
`3.5.1` 的稳定构建可在
[PaperMC 构建元数据](https://fill.papermc.io/v3/projects/velocity/versions/3.5.1/builds)
中确认。本分支不滚动跟踪 Velocity 快照或 Velocity 4.x。

## 功能范围

MultiLogin 允许同一个 Velocity 代理接受官方 Minecraft 认证、多个 Yggdrasil 服务以及
Floodgate 等身份来源。现有代码还包括：

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

[FabricProxy-Lite 2.12.0](https://modrinth.com/mod/fabricproxy-lite/version/v2.12.0) 的测试文件
SHA-256 为
`dca0d05685afaa25d554372ad118d90b6b27f85ded93e6db0b85d822aa29342a`。

本次测试覆盖：

- 代理启动和正常关闭
- Mojang Official 与 DHW PCS Passport 认证
- UUID/profile 与 Modern Forwarding
- 进入 Minecraft 26.2 Fabric 后端
- 聊天及 Minecraft 26.2 ChatSession 包
- 认证拒绝与认证期间断开
- 重复登录冲突
- 正常退出和重连

Minecraft 26.2 实测 ChatSession 包 ID 为 `0x0A`。由于 MultiLogin 会拦截 ChatSession
公钥包，测试后端使用 `enforce-secure-profile=false`；否则后端会拒绝未签名聊天。

本次拓扑没有安装 Floodgate，因此 Floodgate 未经过本次运行验证。

### Minecraft 1.21.11 后端兼容性复测

由于 DHW Inf 服务器仍运行 Minecraft `1.21.11`，本分支另以相同的 Velocity
`3.5.1` build `615`、JDK `25.0.3+9` 和 Modern Forwarding 配置完成了一次针对该版本的
真实客户端复测：

| 组件 | 已测试版本或设置 |
| --- | --- |
| 客户端与后端 | Minecraft `1.21.11` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.141.5+1.21.11` |
| FabricProxy-Lite | `2.11.0` |
| 认证来源 | Mojang Official |

测试使用的 Fabric 服务端启动 JAR SHA-256 为
`ed1b811d75555bd18dfc2b15199d94559ee2aab035e3c92ee43d3c4addca819f`；
[Fabric API 0.141.5+1.21.11](https://modrinth.com/mod/fabric-api/version/zGF3drOQ) 的 SHA-256 为
`2525f9b7bb5524d409404831f7af276a86f7f1e90245ae4bb7555fe088d00e0d`；
[FabricProxy-Lite 2.11.0](https://modrinth.com/mod/fabricproxy-lite/version/nR8AIdvx) 的 SHA-256 为
`ebc7abeaf6c03ac619c701ebb332de6966c6d83edfabf02434720c8fc3d03cdc`。

真实 Minecraft `1.21.11` 客户端使用 Mojang Official 账号完成了两次认证、UUID/profile
转发、进入 Fabric 后端、发送聊天消息、正常退出及重新连接。代理与后端日志没有出现认证、
ChatSession、转发或清理异常。此复测未重复执行 DHW PCS Passport、认证拒绝、冲突登录或
认证期间断开测试；这些路径的运行验证来自上述 Minecraft `26.2` 拓扑。两个拓扑均未测试
Floodgate。

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

构建会拒绝缺少 `-dhw` 后缀的正式版本号。仓库只保留手动触发的正式构建 workflow；它仅允许
从默认分支发布，在运行完整测试后同时上传 Actions artifact，并以
`v<major>.<minor>.<patch>-dhw` tag 创建 GitHub Release、生成发行说明及上传正式 JAR。已有同名
tag 或 Release 时 workflow 会停止，不覆盖已经发布的构件。

首次构建可能生成 Gradle 缓存、各模块的 `build/` 目录、校验后的 Velocity 目标 JAR 和
`.digests`。这些文件均不应提交。目标 JAR 的 SHA-256 不匹配时，构建会立即失败。

## 构件检查

正式交付前至少检查：

- `velocity-plugin.json` 中的 ID、入口点和版本
- 内嵌 `MultiLogin-Core.JarFile` 与 `MultiLogin-Velocity-Injector.JarFile`
- Core 内的 `config.yml`、`mapper.yml` 与 `message.properties`
- manifest 中的 Git revision、Gradle 和 JDK
- 固定 Velocity JAR 的 SHA-256
- `git diff --check`

## 配置资料

配置模板位于 `core/src/main/resources/`。上游
[MultiLogin Wiki](https://github.com/CaaMoe/MultiLogin/wiki) 仅可作为历史参考；其中的安装步骤、
版本要求和配置说明可能已经过时，应以本仓库代码和经过验证的 DHW Inf 配置为准。
