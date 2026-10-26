# 本地 OCR 推理服务

RapidOCR 3.10.0 + ONNX Runtime CPU。模型随 RapidOCR 包安装，无需云账号或密钥；识别图像只在本机内存处理，不保存到磁盘。它是 OCR 基础服务，不是 Agent。

```powershell
# Windows 使用独立 CPython（本机为 3.13），避免 Conda 旧 DLL 冲突
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.lock
.venv/Scripts/python -m uvicorn app:app --host 127.0.0.1 --port 8601 --no-access-log
```

健康检查 GET /health，识别 POST /v1/recognize，原始 JPG/PNG 请求体，最多 5 MiB。Java 后端配置 REPAIR_OCR_PROVIDER=LOCAL、REPAIR_OCR_LOCAL_URL=http://127.0.0.1:8601/v1/recognize、REPAIR_OCR_ENABLED=true。

只绑定回环地址，不暴露给浏览器或公网；Java 适配器也限制回环地址。浏览器继续通过原来受总部权限控制的 OCR 任务接口访问，不增加匿名识别接口。OCR 的任务租约、重试、候选提取、人工确认与采购保存规则保持原样。

如需腾讯云，可选 REPAIR_OCR_PROVIDER=TENCENT 并配置该服务的密钥。不要为了本地方案填写云密钥。

依据：[RapidOCR 安装与模型说明](https://rapidai.github.io/RapidOCRDocs/main/install_usage/rapidocr/install/)。依赖与模型许可保留在所安装的包中，模型未直接复制到本仓库。

当前本机验证为 CPython 3.13.3 + ONNX Runtime 1.23.2，requirements.lock 固定实际依赖；其他平台使用 requirements.txt。健康检查在模型完整预热后才可用，不只判断进程或端口。
