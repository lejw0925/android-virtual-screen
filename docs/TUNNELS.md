# 公网隧道

先开启 MCP，再从设置 → 公网隧道选择方式。关闭、frpc、Cloudflare 随机地址、Cloudflare 固定域名互斥；切换或停止会终止旧进程并清理私有运行文件。界面只有在实际连接建立后才显示公网 MCP 地址。

凭据通过 Android Keystore 加密存储，不参与系统备份；运行文件位于 App 私有目录。日志隐藏 Token、证书、私钥和授权参数。可执行程序由普通 App 进程运行，隧道本身不需要 Root。

## frpc

填写 frps 服务器地址、端口、认证 Token，选择 TCP / HTTP / HTTPS：

- TCP：填写公网监听端口，连接地址为 `http://SERVER:REMOTE_PORT/mcp`。手机本地目标固定为 `127.0.0.1:8732`。
- HTTP：填写已解析到 frps 的域名，并按 frps 配置填写公网 HTTP 端口。
- HTTPS：填写域名与证书、私钥 PEM，配置 frps 的 HTTPS 入口端口。内置 HTTPS-to-HTTP 插件只转发到本 App 的 MCP。

高级模式可粘贴导入 TOML。校验只允许一个 MCP 代理；禁止 visitors、管理入口及任意转发目标。HTTPS 插件的证书与私钥由 App 写入私有文件，不能通过配置指定外部文件路径。

服务器端 frps 由用户提供。基本 TCP 服务端 TOML 示例：

```toml
bindPort = 7000
auth.method = "token"
auth.token = "<your-frps-token>"
```

## Cloudflare 随机地址

选择随机地址后运行官方 Quick Tunnel，解析实际分配的 `https://*.trycloudflare.com/mcp`。无需账号；新建隧道可能产生不同地址，客户端应从 App 复制当前地址。连接失败或断开时不显示可用地址。

## 固定域名：已有 Tunnel Token

在 Cloudflare 配好隧道、域名和 HTTP origin `http://127.0.0.1:8732`。在本 App 填写域名并导入对应 Tunnel Token 后启动。此模式不需要 App 保存账号密码或登录证书。

## 固定域名：官方浏览器授权

1. 选择固定域名，点按「官方浏览器授权」，再打开生成的 Cloudflare 官方页面。
2. 在官方页面登录并选择可用站点。回到 App 等待「授权证书已取得」；浏览器返回不等于授权成功。App 以 cloudflared 实际收到并校验的证书为准，等待超时会明确报错。
3. 列出隧道与证书授权站点的域名。选择已有隧道取得凭据，或输入隧道名称创建新的隧道。
4. 选择或填写该站点下的主机名，创建 DNS 路由。已有冲突记录返回 `DNS_RECORD_CONFLICT`，不会自动覆盖。
5. 启动连接，等待健康连接建立后复制地址。

界面显示授权、凭据就绪、隧道配置、DNS 绑定与连接状态。使用官方 cloudflared 命令及 Cloudflare API，不抓取账号网页。

## 诊断

设置页可查看连接状态和经过隐藏处理的日志。断网后由隧道程序及进程管理器重连；重连不会重放 MCP 设备操作。MCP 停止时也停止隧道。用户名、密码只在 Cloudflare 官方浏览器页面输入。

目前实体机验证范围和账号相关的待验证流程记录在 [VALIDATION.md](VALIDATION.md)。
