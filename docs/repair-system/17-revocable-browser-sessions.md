# 可撤销的浏览器登录会话

## 令牌和 Cookie

登录成功只在 JSON 中返回 Access Token、访问过期时间和会话过期时间。Access Token 只保存在页面内存，刷新页面通过 HttpOnly Refresh Cookie 恢复。启动时清除旧 repair_auth_tokens 本地存储，不将旧令牌迁入新会话；不向 localStorage 或 sessionStorage 写入任何认证令牌。

默认访问令牌 15 分钟，会话绝对期限 7 天，刷新轮换不会无限延长会话寿命。每次刷新通过 Redis 原子消费旧刷新 jti，再签发新令牌；同一 sid 保持稳定。

生产默认 Cookie 名 __Host-repair_refresh，HttpOnly=true、Secure=true、SameSite=Lax、Path=/，不设置 Domain。只接受 Cookie 中的刷新令牌，不接受 JSON 刷新令牌，JSON 响应也不返回刷新密钥。仅本机 HTTP 开发显式设置 REPAIR_AUTH_COOKIE_SECURE=false，使用 repair_refresh_local，仍为 HttpOnly 和 host-only。跨站部署确有需要时设置 REPAIR_AUTH_COOKIE_SAME_SITE=None，必须同时使用 HTTPS/Secure，并配置精确的前端来源。

## 立即撤销

V009 新增 repair_login_sessions，保存 sid、用户、认证版本、绝对过期时间和撤销时间，不保存原始令牌。所有带 JWT 的请求都检查服务端会话；业务接口继续实时校验有效成员、权限版本与数据范围。旧版没有 sid 的令牌一律拒绝，升级后需要重新登录。

退出撤销当前 Cookie 对应 sid；Cookie 缺失时可用内存中的有效 Access Token 撤销对应 sid。退出端点单独验证可选 Bearer，因此过期或已撤销的访问令牌不会阻碍清除有效 Cookie；其他业务接口保持原来的严格 Bearer 验证。该会话包括刷新前签发的全部 Access Token 都无法再次通过身份检查，其他设备独立 sid 保持有效。退出成功后清除 Cookie，失败时前端保留明确的失败反馈并支持重试，不提前宣称撤销完成。跨标签页通过 BroadcastChannel 同步退出；同源标签页的 Cookie 刷新和退出使用 Web Locks 串行处理，页面内还有共享刷新 Promise。

密码重置成功时在一个数据库事务中更新密码、递增成员 auth_version、撤销该账号全部 sid，并撤销 Redis 刷新资格。登录凭证检查、会话签发和密码重置对同一用户行加锁，防止旧密码校验与重置交错导致重新签发。刷新仍绑定原 sid，退出和刷新并发时不能创建新会话复活。服务端会话查询故障不会放行请求。已经进入执行阶段的请求不进行中途回滚；撤销后新的鉴权请求会被拒绝。

## CSRF 与跨域

认证写接口和邀请接受接口必须携带 X-Repair-CSRF: 1，并校验存在的 Origin 精确匹配 repair.cors-origins；无 Origin 且 Sec-Fetch-Site=cross-site 的请求被拒绝。专用 AJAX 请求头配合严格 CORS 阻止简单跨站表单和未获准来源的凭据请求。业务接口仍只接受 Authorization Bearer，不把自动携带的 Cookie 当作业务认证。CORS 仅允许明确来源、允许 credentials 和专用头，前端 fetch 使用 credentials: include。

同源生产部署推荐 /api 反向代理。独立前端域名通过 REPAIR_CORS_ORIGINS 配置；不可使用通配来源。HTTPS 终止于反向代理时仍保持 Secure=true。

## 验证范围

真实隔离 MySQL / Redis 回归覆盖 Cookie 属性、不返回刷新令牌、CSRF、刷新防重放、当前设备退出、其他设备保留、密码重置全设备失效和退出/刷新并发。浏览器覆盖内存存储、HttpOnly 不可读取、刷新页面恢复、跨标签退出、退出网络失败反馈，以及旧 Access Token 立即被拒绝。验证码测试仅向隔离 Redis 写入测试码，不发送邮件。

参考：[OWASP 会话管理](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)、[OWASP CSRF 防护中的自定义 AJAX 请求头与来源校验](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)。

## 本次验收结果

2026-10-27 完整隔离回归 100 项通过，失败、错误、跳过均为 0；包含 7 组实际浏览器流程、真实 MySQL / Redis / Canal 和可执行 jar。前端 TypeScript / Vite 构建通过。

实际本地服务已应用 V009 并重启。使用原有总部、工程师账号确认 HttpOnly Cookie、刷新页面恢复、退出后旧 Access Token 立即返回 401、其他账号不受影响；没有重置现有账号密码或推进原有工单状态。Canal 读缓存 cdcFresh=true。

日志与截图只保存于被忽略的 target/session-security-full.log、target/session-security-summary.json、target/repair-session-browser、target/session-live-preview；前端构建日志在 .local/session-build.log。上传清单再次扫描，不包含 AGENTS、助手配置、私有凭据、运行数据、依赖与构建产物。
