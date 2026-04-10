# SPW AI Playlist

SPW AI Playlist 是一款面向 [Salt Player for Windows](https://github.com/Moriafly/SaltPlayerForWindows) 的第三方插件。

当你在 SPW 主界面的搜索框中输入自然语言需求并按下回车后，它会自动读取曲库、调用远程大模型与向量接口，在本地曲库中检索出最符合语义的歌曲，并直接替换播放队列、开始播放。

## 演示视频

### 场景 1：语义理解与推荐

<video src="assets/videos/showcase3.mp4" controls type="video/mp4"></video>

### 场景 2：基于情绪与氛围

<video src="assets/videos/showcase2.mp4" controls type="video/mp4"></video>

### 场景 3：精确语义检索

<video src="./assets/videos/showcase1.mp4" width="100%" controls="controls"></video>

- 开源地址：https://github.com/AugustuXue/spw-ai-playlist
- 下载地址：https://github.com/AugustuXue/spw-ai-playlist/releases

## 功能

- 监听 SPW 主界面搜索框的回车操作
- 从 SPW 宿主读取本地曲库信息
- 自动读取歌词并生成用于检索的文本
- 调用大语言模型生成歌曲的主观场景化描述
- 调用 Embedding 接口生成向量并存入插件自带 SQLite 数据库
- 基于本地向量库进行相似度检索
- 叠加歌曲标题 / 歌手 / 专辑的元数据加权，提高精确命中率
- 将匹配结果重建为播放队列并立即播放
- 支持后台增量同步，避免每次都全量重算
- 支持查询扩展开关，让更宽泛的自然语言搜索更容易命中
- 支持文件日志，方便排障

## 安装与使用

### 1. 下载插件包

前往 [Releases 页面](https://github.com/AugustuXue/spw-ai-playlist/releases)下载最新版本的插件压缩包：

### 2. 导入到 SPW

1. 打开 SPW
2. 进入 `设置` → `创意工坊` → `模组管理`
3. 点击右上角的导入模组
4. 选择下载好的插件 zip 文件
5. 导入完成后，启用该插件
6. 重启或重新打开 SPW 以确保插件正常加载

### 3. 填写插件配置

启用插件后，在 SPW 的插件设置中填写对应参数。

#### 向量模型（Embedding）

- `remote_ai.embedding_api_key`
  - 向量接口的 API Key
- `remote_ai.embedding_model`
  - 向量模型名称，默认：`Qwen/Qwen3-Embedding-8B`
- `remote_ai.embedding_base_url`
  - 向量接口地址，默认：`https://api.siliconflow.cn/v1/embeddings`

#### 大语言模型（LLM）

- `remote_ai.chat_api_key`
  - 聊天补全接口的 API Key
- `remote_ai.chat_model`
  - 聊天模型名称，默认：`deepseek/deepseek-v3.2-251201`
- `remote_ai.chat_base_url`
  - 聊天接口地址，默认：`https://api.qnaigc.com/v1/chat/completions`

#### RAG 检索与处理

- `rag.thread_count`
  - 并发处理线程数，默认：`1`
- `rag.max_playback_items`
  - 每次搜索后最多加入播放队列的歌曲数，默认：`20`
- `rag.request_delay_ms`
  - 两次 API 请求之间的延迟，默认：`1000`
- `rag.enable_query_expansion`
  - 是否启用查询扩展，默认：`false`

#### 日志与排障

- `debug.enable_file_log`
  - 是否启用文件日志，默认：`false`

## 使用说明

1. 打开 SPW
2. 在主界面的搜索框中输入你想听的内容，例如：
   - `下雨天适合听的歌`
   - `周杰伦`
   - `适合写代码的纯音乐`
   - `悲伤一点但不要太慢`
3. 按下回车
4. 插件会自动：
   - 读取你的搜索词
   - 生成或扩展检索语义
   - 在本地曲库中计算匹配结果
   - 替换当前播放队列
   - 从第一首开始播放

### 搜索行为说明

- 插件拦截的是 **SPW 主界面的搜索框回车**
- 只有搜索框文本能被正确读取时才会触发
- 搜索后会直接重建队列，不是简单追加
- 默认只会保留相似度较高的结果，低分结果会被过滤掉
- 如果开启了查询扩展，系统会先判断当前输入是否属于“宽泛意图”
  - 例如：`适合`、`推荐`、`想听`、`帮我找`、`类似`、`氛围`、`背景音乐`、`歌单`、`听歌`、`播放`
  - 这类词更容易触发 LLM 扩展
  - 像 `王菲 粤语歌曲` 这类带明确约束的查询不会触发LLM扩展

## 成本估算

本插件的成本主要分为两部分：**离线预处理** 和 **在线搜索**。

### 1. 离线预处理成本

离线预处理阶段会对本地曲库中的每首歌生成主观描述并写入向量库。以如下模型作参考：

- 向量模型（Embedding）： 硅基流动 `Qwen3-Embedding-8B` `¥0.00028 / K Tokens`
- 大语言模型（LLM）：七牛云  `deepseek-v3.2-251201` 输入 `¥0.002 / K Tokens`，输出 `¥0.003 / K Tokens`

按实测估算，单首歌曲的平均成本约为：

- LLM 主观描述：`¥0.0011`
- Embedding 向量化：`¥0.00019`
- 合计：**约 `¥0.00129 / 首`**

如果处理一个 **500 首** 的标准本地曲库，总成本大约是 **`¥0.645`**。

### 2. 在线搜索成本

#### 未开启查询扩展

只调用一次 Embedding 接口做语义检索，用户输入约 20 Tokens 时：

- Embedding 成本：`0.02 × 0.00028 ≈ ¥0.0000056`

也就是说，**每搜索 1000 次，成本约 `¥0.0056`**。

#### 开启查询扩展

开启 `rag.enable_query_expansion` 后，搜索流程会额外多一次 LLM 请求，用来把简短查询扩展成更适合检索的语义描述。

按一次扩展示例粗略估算：

- LLM 输入：约 150 Tokens → `150 / 1000 × 0.002 ≈ ¥0.0003`
- LLM 输出：约 50 Tokens → `50 / 1000 × 0.003 ≈ ¥0.00015`
- Embedding：约 20 Tokens → `¥0.0000056`

合计约为：**`¥0.00046 / 次`**

也就是说，**每搜索 1000 次，成本约 `¥0.46`**。

> 说明：上面的“开启查询扩展”成本是按常见短查询做的保守估算，实际会随提示词长度、模型输出长度和服务商计费策略略有波动。

## 注意事项

### 1. 需要配置 API Key

这个插件依赖远程 Embedding 和 LLM 接口。如果没有填写密钥，插件无法完成索引同步或搜索。

### 2. 会产生接口调用成本

首次同步整个曲库时，插件会对歌曲进行描述生成和向量化，后续搜索也会调用 Embedding 接口。请根据自己的服务商计费方式合理使用。

### 3. 通过反射接入宿主实现

插件通过反射读取 SPW 宿主内部对象、曲库和播放控制器，不依赖官方公开播放控制 API，因此：

- 对 SPW 版本变化较敏感

### 4. 首次启动可能较慢

首次运行会：

- 初始化 SQLite 数据库
- 读取曲库
- 后台同步歌曲向量
- 回填历史记录的元数据

曲库越大，首次同步时间越长。

### 5. 搜索结果不等于“精确歌曲名搜索”

这是一个语义检索插件，不是普通关键字过滤器。

- 输入歌名 / 歌手名通常能命中
- 输入“情绪”“场景”“风格”类描述更符合这个插件的强项
- 如果你只想做严格关键字检索，建议直接用 SPW 原生搜索

### 6. 日志文件

若开启 `debug.enable_file_log`，日志会写入插件数据目录下：

`logs/plugin-debug.log`

日志文件超过一定大小后会自动清理，避免无限增长。

## 技术思路

### 总体架构

本插件采用“**本地预处理 + RAG 检索 + 反射播放控制**”的思路：

1. **读取曲库**
   - 通过反射访问 SPW 宿主数据库
   - 拿到 Track 列表

2. **构建检索文本**
   - 拼接歌名、歌手、专辑、年份
   - 读取并清洗歌词
   - 调用 LLM 生成简短的主观描述
   - 将这些内容合并为 embedding 输入文本

3. **向量化并入库**
   - 调用 Embedding API 生成向量
   - 将 `track_id`、`source_text`、`vector`、`updated_at` 等信息写入 SQLite

4. **搜索时检索**
   - 用户输入 query
   - 可选做 query expansion
   - 对 query 调用 Embedding 接口生成查询向量
   - 在本地 SQLite 中遍历向量并计算 cosine similarity
   - 结合标题、歌手、专辑做 metadata bonus
   - 按总分取 Top-N

5. **控制播放**
   - 通过反射构造宿主播放对象
   - 调用播放控制器清空并重建队列
   - 立即从第一首开始播放

### 数据存储

插件自带 SQLite 数据库，数据库文件保存在插件自己的数据目录中，而不是写死到源码目录。

表结构核心字段：

- `track_id`
- `source_text`
- `vector`
- `updated_at`
- `title`
- `artist`
- `album`

这样做的好处是：

- 数据随插件一起保存
- 升级插件时不容易丢库
- 便于做增量更新和元数据回填

### 性能与稳定性设计

- 使用 SQLite WAL 模式，减少读写互斥
- 只在需要时读全量 Track ID，避免加载全量大对象
- 启动时缓存反射对象，减少重复反射开销
- 缓存曲库映射，避免每次回车都重建索引
- 搜索任务会取消前一次未完成的请求，避免并发重复触发
- 对 API 限流错误做提示与降级

## 配置项说明

### `remote_ai.embedding_api_key`
Embedding 接口鉴权密钥。

### `remote_ai.embedding_model`
Embedding 模型名。

### `remote_ai.embedding_base_url`
Embedding 接口地址。

### `remote_ai.chat_api_key`
LLM 聊天接口鉴权密钥。

### `remote_ai.chat_model`
LLM 模型名。

### `remote_ai.chat_base_url`
LLM 聊天接口地址。

### `rag.thread_count`
并发同步线程数。默认使用较保守的单线程配置，适合不确定接口限流策略的情况。

### `rag.max_playback_items`
每次检索后最多加入播放队列的歌曲数。

### `rag.request_delay_ms`
同步阶段的请求间隔。适当增大可以减少限流风险。

### `rag.enable_query_expansion`
开启后，系统会在查询较宽泛时让 LLM 先扩展搜索意图，再做 embedding 检索。

### `debug.enable_file_log`
开启后写入文件日志，方便排障。

## 开发与构建

### 环境要求

- JDK 21
- Gradle

### 构建插件包

```bash
./gradlew plugin
```

构建后会在 SPW 创意工坊插件目录生成 zip 包，便于直接导入。

### 生成的插件包

插件包的名字会跟版本号相关，实际以构建产物为准。

## 常见问题

### 为什么按回车没有反应？

请检查：

1. 是否已启用插件
2. 是否在 SPW 主界面的搜索框中输入内容
3. 搜索框是否处于可读取状态
4. 是否已正确填写 Embedding API Key
5. 是否启用了文件日志，方便查看具体报错

### 为什么提示没有找到歌曲？

可能原因：

- 曲库里没有接近的语义结果
- API 返回异常
- 当前曲库还在同步中
- 输入太短或太噪声

### 为什么返回结果不完全等于歌名匹配？

因为这是语义检索插件。它会同时参考：

- 歌词
- AI 生成描述
- 用户输入语义
- 标题 / 歌手 / 专辑元数据

所以它更适合“找气氛”“找风格”“找场景”，也能兼顾“歌手名”“歌名”这种精确条件。
