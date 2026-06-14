# 珍珠 AI 鉴定云托管后端

这是给“珍珠 AI 鉴定”微信小程序使用的 Spring Boot 后端服务，项目结构参考微信云托管 Spring Boot 模板。

## 功能

- `GET /`：服务健康检查
- `GET /api/health`：接口健康检查
- `POST /api/pearl/analyze`：珍珠图片 AI 初筛接口
- `POST /api/pearl/analyze/stream`：珍珠图片 AI 初筛流式接口，返回 `application/x-ndjson`

后端负责保存火山方舟密钥、转发图片给豆包视觉模型、解析模型 JSON，并返回小程序结果页可直接使用的数据结构。

## 为什么 ARK_API_KEY 放后端环境变量

`ARK_API_KEY` 应该放在云托管应用环境变量里，不建议放数据库，更不能放小程序前端。

- 放环境变量：适合运行密钥，部署时注入，代码仓库不泄露，轮换方便。
- 放数据库：不适合 API Key，读取链路更长，还需要额外保护数据库访问权限。
- 放小程序前端：不安全，小程序包可能被反编译，key 会泄露并被刷额度。

推荐配置：

```bash
ARK_API_KEY=ark-xxxxxxxx
ARK_MODEL=doubao-seed-1-8-251228
ARK_API_URL=https://ark.cn-beijing.volces.com/api/v3/chat/completions
ARK_TIMEOUT_MS=60000
```

## 请求示例

```bash
curl -X POST http://localhost:80/api/pearl/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "mode": "quick",
    "images": [
      {
        "title": "整体照",
        "dataUrl": "data:image/jpeg;base64,/9j/..."
      }
    ]
  }'
```

## 流式接口

小程序默认建议调用：

```text
POST /api/pearl/analyze/stream
```

响应按行返回 NDJSON：

```json
{"type":"start","message":"AI 正在鉴定图片，请稍候..."}
{"type":"delta","message":"正在观察光泽","reasoningText":"正在观察光泽","answerText":""}
{"type":"report","message":"AI 鉴定完成，正在生成报告...","report":{}}
```

其中：

- `delta`：用于小程序实时展示 AI 分析过程。
- `report`：最终结构化报告，小程序保存历史和跳转结果页使用。
- `error`：服务端或火山方舟异常。

也可以传公网图片：

```json
{
  "mode": "complete",
  "images": [
    {
      "title": "整体照",
      "url": "https://example.com/pearl.jpg"
    }
  ]
}
```

## 返回结构

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": "PP1710000000000",
    "createdAt": "2026-06-14T10:00:00Z",
    "result": "疑似淡水珍珠",
    "authenticity": "真",
    "pearlType": "淡水珍珠",
    "confidence": 85,
    "qualityGrade": {
      "grade": "B",
      "level": "微瑕",
      "score": 82,
      "description": "极少针点状瑕疵，肉眼较难发现",
      "label": "B 微瑕"
    },
    "attributes": [
      {
        "name": "珍珠类型",
        "value": "淡水珍珠",
        "detail": "检测为“真”",
        "score": 80
      }
    ],
    "summary": "AI 初筛总结",
    "reasons": ["依据1", "依据2"],
    "suggestions": ["建议1"]
  }
}
```

## 本地运行

本机需要 Maven：

```bash
cd pearl-cloudrun-springboot
export ARK_API_KEY="你的火山方舟 key"
mvn spring-boot:run
```

访问：

```bash
curl http://localhost:80/api/health
```

## Docker 构建

```bash
docker build -t pearl-cloudrun-springboot .
docker run -p 80:80 \
  -e ARK_API_KEY="你的火山方舟 key" \
  pearl-cloudrun-springboot
```

## 微信云托管部署

1. 在微信开发者工具或微信云托管控制台新建服务。
2. 代码仓库选择本目录对应的 Git 仓库。
3. 构建方式选择 Dockerfile。
4. 服务端口设置为 `80`。
5. 在服务环境变量里添加 `ARK_API_KEY`。
6. 部署成功后，把云托管访问域名配置到小程序 request 合法域名。
7. 小程序端请求：`https://你的云托管域名/api/pearl/analyze`。

## 上传到 Git

如果这个后端单独作为一个仓库：

```bash
cd pearl-cloudrun-springboot
git init
git add .
git commit -m "init pearl cloudrun backend"
git branch -M main
git remote add origin 你的Git仓库地址
git push -u origin main
```

如果要和小程序放在同一个仓库，可以把 `pearl-cloudrun-springboot` 作为后端子目录提交。
