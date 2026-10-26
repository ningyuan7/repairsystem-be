# 已抽取的基础能力

当前项目已经物理独立，不再依赖原项目。下列代码均已复制到 backend 并由本工程编译、维护：

| 能力 | 本项目源码 |
| --- | --- |
| 账号、密码、JWT、验证码、登录审计 | src/main/java/com/gaofeng/auth |
| 账号存储与 Mapper | src/main/java/com/gaofeng/user |
| Redis 原子限流和业务异常 | src/main/java/com/gaofeng/common |
| 图片解码和重新编码 | src/main/java/com/gaofeng/storage/ImageSanitizer.java |
| 门店、成员权限、工单、采购、OCR、通知与统计 | src/main/java/com/gaofeng/repair |

仅复制实际需要的类；没有复制社区、聊天、评论、AI、Kafka、ES、Canal、OSS 业务与配置。复用代码在这里是本项目自己的源码，不通过相对路径、共享模块或符号链接引用其他仓库。原账号数据和真实配置不随代码迁移。

当前运行方式见 [独立项目说明](../standalone-project.md)。
