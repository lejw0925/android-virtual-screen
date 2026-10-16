# 来源与许可

虚拟屏、捕获、输入、旋转等部分代码改编自 [Mangi-11/Eta](https://github.com/Mangi-11/Eta)，保留 ETA 的 PolyForm Noncommercial 1.0.0 许可和 Required Notice。对应许可见根目录 `LICENSE` 与 `app/src/main/assets/licenses/ETA.txt`。

Required Notice: Copyright © 2026 蛮吉 (Mangi-11).

| 组件 | 来源 | 许可文件 |
| --- | --- | --- |
| AndroidX Compose Material 3 / Material Icons | [androidx/androidx](https://github.com/androidx/androidx) | `assets/licenses/AndroidX.txt`，Apache-2.0 |
| miuix 0.9.4（历史 UI；迁移后保留通知） | [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix/tree/v0.9.4) | `assets/licenses/miuix.txt`，Apache-2.0 |
| frpc 0.65.0 | [fatedier/frp](https://github.com/fatedier/frp/tree/v0.65.0) | `assets/licenses/frp.txt`，Apache-2.0 |
| cloudflared 2025.8.1 | [cloudflare/cloudflared](https://github.com/cloudflare/cloudflared/tree/2025.8.1) | `assets/licenses/cloudflared.txt`，Apache-2.0 |

原生补丁位于 `scripts/patch-native.py`，构建脚本位于 `scripts/build-tunnels.sh`。源码归档、补丁与三 ABI 可执行文件的 SHA-256 记录在 `app/src/main/assets/native-build.json`。App 的「关于 → 开源许可与构建来源」展示组件许可文本；构建来源与二进制哈希保留在仓库和 APK 资产中，避免向普通用户展示 JSON。
