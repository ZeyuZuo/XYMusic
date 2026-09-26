# 首页阶段 0：最小接口核对

核对日期：2026-09-26。上游 `KuGouMusicApi` 提交：`590ff03b3b9aa766be6fd03bf4d09f9127482fc5`。范围来自 [HOME_PLAN 阶段 0](HOME_PLAN.md#阶段-0最小接口核对)。

结论：已完成两次成功首批响应、对应请求源码和缓存行为的最小核对。已确认日推的基本字段与 FM 的结构差异；日期语义、计数一致性、时长单位、异常响应以及 FM 补歌/反馈语义仍有未确认项，见末尾清单。此次没有接通首页业务或新增业务 DTO。

## 证据与执行范围

- 经用户明确授权，在开发模拟器内读取现有加密会话，经仅监听电脑本机的临时服务，各请求一次日推和 FM 首批。复用上游原模块及请求实现，普通版配置，两个请求的 `platform` 均显式为 `android`；未切换普通版/概念版 token。
- 临时服务绕开 HTTP 缓存层，关闭上游正文/异常日志，只返回字段类型、条数、业务状态和必要的汇总统计。真实歌曲标识、标题、账号、设备标识、签名、认证和完整响应不落盘。脱敏摘录见 [结构观察记录](samples/home-stage0-observation.json)，该文件不是可直接喂给业务解析器的 API 响应样本。
- FM 请求使用 `action=play`、`mode=normal`、`remain_songcnt=0`、JSON 布尔 `is_overplay=false`；不提供 hash、songid、playtime，不显式提交歌曲播放/完成反馈或 garbage。不据此推导服务端内部没有推荐曝光等事件。
- 最初临时工具的 Cookie 空格解析在本机校验处失败，未调用上游；修正后两个上游请求均成功，没有额外重试或批次请求。没有发送短信、登录/刷新账号或修改云端歌单。
- 核对工具源码、临时 Gradle 配置、设备测试包、端口转发和本机服务均已清理；主应用和会话保留。上游未修改；核对前后均有既存的未跟踪 `package-lock.json`，未处理它。

## 请求与中转层

| 项目 | 已确认行为 | 客户端实施约束 |
| --- | --- | --- |
| 日推模块 | `/everyday/recommend` → 网关 POST `/everyday_song_recommend`，路由头 `everydayrec.service.kugou.com` | 显式 `platform=android`；模块只转发 platform，没有 page/pagesize |
| FM 模块 | `/personal/fm` → 网关 POST `/v2/personal_recommend`，路由头 `persnfm.service.kugou.com` | 首批参数如上；补歌与反馈参数继续按实际语义核对 |
| 请求协议 | 上游 server 支持 GET/POST，JSON body 可传参数；账号与设备由 Cookie 提供 | Android 侧认证仅放现有 Cookie/请求体/头，不拼到 API URL |
| 成功响应 | 两次均由请求封装返回 HTTP 200，正文数字 `status=1`、`error_code=0` | 同时验证业务状态和必要结构，不只判断 HTTP 200 |
| 失败封装 | `util/request.js` 遇正文 status=0 或非零 error_code，通常包装成 HTTP 502；网络异常也可能是 502 | 不能把所有 502 解释为认证失败；只记录受限数字状态，不能打印异常正文 |

只读源码：`module/everyday_recommend.js`、`module/personal_fm.js`、`util/request.js`、`server.js`、`util/apicache.js`，以及 `docs/README.md` 的每日推荐/私人 FM/调用前须知、`interface.d.ts` 的对应参数声明。没有复制或改写上游实现。

## 每日推荐：已观察到的结构

列表位于 `data.song_list`，本次 30 条，30 个 hash 均为 32 位十六进制字符串且彼此不同。这个样本不能证明每天固定 30 首，也不能证明源列表始终无重复；客户端必须按返回数组顺序保留全部条目。

| 路径（歌曲字段均相对 `data.song_list[]`） | 本次类型/存在情况 | 后续映射依据 |
| --- | --- | --- |
| `hash` | string，30/30 | Song.hash，不能用 songid 替换 |
| `album_id` / `album_audio_id` / `mixsongid` | string，均 30/30 | 保留原标识；优先使用明确的 album_audio_id，未证明与 mixsongid 可无条件互换 |
| `songid` | number，30/30 | 30 条中与 album_audio_id 比较全部不同；必须独立于播放专辑音频 ID |
| `songname` / `official_songname` / `ori_audio_name` / `filename` | string，均 30/30 | 已确认候选字段存在，展示优先级与空串处理在映射时明确，不靠拆 filename 猜歌手 |
| `singerinfo` | array，30/30；观察到元素 `id/name/is_publish` 均为 string | 优先保留完整歌手数组；另有 string `author_name`，不预设其分隔符 |
| `album_name` | string，30/30 | 专辑名称 |
| `sizable_cover` | string，30/30 | 本次均为 HTTP 且含 `{size}`；可复用 ArtworkUrl 的尺寸替换和 HTTPS 规范化，实际图片加载未验证 |
| `time_length` | number，30/30，范围 159–348 | 呈秒量级，但本次未对照已知曲长或播放元数据证明单位；不能当已验证的毫秒值 |
| `data.creation_date` | string | 仅保留了类型，未核对具体格式、日期含义或时区 |
| `data.song_list_size` | number | 仅保留了类型，未记录数值，尚未验证与数组长度是否一致 |
| `data.cover_img_url` / `data.sub_title` | string | 列表级封面/文案候选，不能当作歌曲封面或可信推荐日期 |

响应还包含 `tracker_info.auth`，顶层 data 包含设备/签名相关字段。后续映射必须采用字段白名单，只缓存公开歌曲元数据和已核实的日期信息，不能直接保存整个 JSON 或整条 song_list 元素。

**完整性与日期结论**：当前模块不转发分页参数；不能凭这一个 30 条样本发明分页协议或设置 30 首上限。阶段 1 的首次正式读取需核对 song_list_size 的值；若它确实表示总条数且与数组长度不符，应明确报告未取齐，不能悄悄点播一份被当作完整歌单的部分列表。creation_date 的格式与语义也需继续核对，未确认前不声称它表示“今天”，不猜北京时间零点刷新。

## FM 首批：已观察到的结构

同样为 `data.song_list`，本次 5 条，hash 均为不同的 32 位十六进制字符串。未进行第二批请求，不以此认定固定批量或续播能力。

| 字段 | 本次观察 | 实施边界 |
| --- | --- | --- |
| `hash` / `album_id` / `mixsongid` | string，均 5/5 | 保留真实字段；mixsongid 与播放 album_audio_id 的对应仍待验证 |
| `songid` | number，5/5 | 为 FM 请求 songid 的候选来源；未提交反馈验证，不能改用 entryId 或 album_audio_id |
| `songname` / `official_songname` / `ori_audio_name` / `filename` / `author_name` | string，均 5/5 | 不直接复用日推整条响应 DTO |
| `singerinfo` | array，5/5 | 观察到元素 `id/name/is_publish` 字符串 |
| 顶层 `album_audio_id` / `album_name` / `sizable_cover` | 均缺失 | 不能编造为 songid、空成功对象或沿用上首歌曲资料 |
| `time_length` | number，5/5，范围 181–294 | 单位需再核对；与请求 playtime 的单位是两件事 |
| `rec_song_info` | object，含 `alg_path/rec_desc/rec_desc2/similar_desc` 字符串 | 推荐来源结构存在，具体使用随 FM 阶段设计，搜索插入不能套用它 |
| `relate_goods[]` | 第一首歌曲观察到 4 个变体，含 hash、number album_audio_id、string album_id/albumname、info.image/info.duration | 只核对了第一首歌曲的嵌套结构；未核对变体 hash 与当前选择的匹配，不能直接拿第一个变体的 ID/封面 |
| `data` 控制字段 | algorithm_id、cur_mark、filter_num、fresh_mode、hotsong_num、is_clean、sync_point 等 | 类型已见，语义未确认；不据名称自行清空或重置队列 |

FM 响应也含 tracker_info.auth 等不应进入缓存的字段。自动补歌去重、用户插入重复和推荐来源标识仍按 HOME_PLAN 分开处理。

## FM 参数的源码验证

用上游模块和捕获请求配置的模拟 useAxios 做了本地断言，不连接网络：

- action 默认 `play`，`garbage` 的含义来自上游文档；本轮没有调用 garbage。
- `is_overplay` 使用 JavaScript 真值判断。字符串 `"0"` 会被转成 **1**；实际请求必须传 JSON false/0，不能按字符串传 false 或 0。
- 数字 `playtime=0` 被省略，字符串 `"0"` 会被保留。其他 playtime 值原样转发，没有单位转换；源码、文档和类型声明均未说明单位。不能用模块 clienttime 的毫秒时间戳来推导 playtime 也是毫秒。
- hash/songid/playtime 都是条件加入，首次不提供时确实不进入 FM 请求体。
- remain_songcnt 转为数字，值 5 仍会发往上游；“大于 4 不返回歌曲”是上游文档约定，不是本地短路行为，本次未发请求验证该阈值。
- 已播反馈与补歌参数进入同一次请求，协议上存在耦合的可能；不能据此认定每次下一首都必须发送反馈。实际事件语义、试听完成和错误后的重试规则仍待确认。

## 中转缓存核对与决定

`server.js` 对 HTTP 200 启用 2 分钟 apicache，默认键是 **hostname + originalUrl**。默认 appendKey 为空；Cookie、Authorization、HTTP 方法及 POST body 均不参与键。

用上游实际中间件和本机模拟路由验证，所有数据均为合成标记，没有调用酷狗：

1. 相同 URL 的 POST 请求，模拟 A 的结果会被模拟 B 复用，即使请求体不同。
2. 同 URL 的 GET 也会命中之前的 POST 缓存。
3. `X-Apicache-Bypass: 1` 跳过缓存读取与写入，取得新的模拟结果，且不覆盖原缓存。

**后续日推/FM 请求使用该明确支持的 bypass 头**，日推需要的账号/日期缓存由 Android 自己维护；FM 不使用通用 HTTP 响应缓存。不添加时间戳或账号 ID 到 URL，也不把客户端缓存误当成服务端账号隔离。本轮只核对，没有修改 Android API 声明或上游缓存实现。已有其他账号接口是否需要相同处理属于后续独立排查范围。

## 未确认项与下一步

| 未确认项 | 当前证据限制 | 后续安排 |
| --- | --- | --- |
| 日推日期、总量与分页完整性 | 日期/总量只保留类型；模块无分页参数，样本仅一批 | 阶段 1 首次读取时核对安全的日期格式、计数值及长度关系，再落实缓存日期和完整性检查 |
| 曲长单位 | 仅观察 time_length 的数值范围 | 映射前与已知曲长/实际播放器时长对照；客户端最终统一为毫秒 |
| 认证失败、空列表和空批次 | 两次均成功且非空；没有清除或伪造真实会话请求 | 自动化只构造明确标注的合成错误/空数据；真实错误码按后续实际遇到的响应核对 |
| FM 补歌参数、重复批次和剩余数阈值 | 仅首批，无第二批和 remain>4 请求 | FM 阶段再进行受控只读联调，禁止无限循环请求 |
| FM 推荐 ID、变体与播放字段关联 | 有 songid/mixsongid/变体 album_audio_id，未验证反馈或变体匹配 | 保留区别；播放 ID 和推荐来源分别映射，不能猜对应关系 |
| playtime 单位、反馈成功码、完成与试听语义 | 上游未说明，阶段 0 没有真实反馈 | FM 反馈阶段核实后实现；真实反馈必须由用户明确触发 |

本次仅交付协议核对文档与脱敏观察记录。下一步仍先完成队列前置人工验收，再进入阶段 1 的每日推荐；阶段 1 的首次正式读取同时补齐上表中的日推映射细节。阶段 0 的成功首批不等于日推/FM 功能已接通，也不等于全部协议语义已经验证。

## 验证记录

临时手动核对工具构建成功；两次真实首批请求和本地参数/缓存断言完成。移除工具后运行 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 成功，正常应用源码未变，已有 68 项测试对应任务命中 Gradle 缓存；本轮没有新增依赖真实账号的自动化测试。脱敏 JSON 可解析，`git diff --check` 通过。仅 README、本计划链接文档和观察记录待提交。
