# MultiLogin

本仓库是 [DHW PCS](https://github.com/DHW-PCS) 为 DHW Inf 服务器保留的临时维护分支。它只用于在 DHW Inf 从原 MultiLogin 方案迁移到替代认证系统期间维持现有服务，不是对上游项目的长期续作，也不面向其他服务器提供通用发行版。

> [!WARNING]
> 不建议在 DHW Inf 以外的任何服务器部署本分支。DHW PCS 不对其他使用场景的兼容性、稳定性、安全性、数据完整性或后续维护作任何明示或默示担保，也不为此类部署提供支持。

本仓库不接受 Pull Request。现有 MultiLogin 服务器应尽快制定迁移计划；推荐迁移到 [超域登录（HyperZoneLogin）](https://github.com/HyperZoneLogin/HyperzoneLogin)，而不是将本临时分支作为新的长期依赖。HyperZoneLogin 是面向 Velocity 网络的多认证流程框架，提供 Yggdrasil、Floodgate 等认证模块及旧系统迁移工具。

目标版本、构建方法、本机验证拓扑和构件检查记录见 [TECHNICAL.md](TECHNICAL.md)。

本仓库派生自 [CaaMoe/MultiLogin](https://github.com/CaaMoe/MultiLogin)，继续依照 [GNU General Public License v3.0](LICENSE) 发布。
