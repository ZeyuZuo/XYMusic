# 云端音乐库：只读接口核对

日期：2026-09-26。分支：`feature/cloud-library`。上游 `KuGouMusicApi` 基线：`590ff03b3b9aa766be6fd03bf4d09f9127482fc5`。

结论：当前账号的系统喜欢、自建歌单、真实空歌单，以及搜索公开歌单的读取路径和分页边界已验证，可以开始音乐库列表实现。收藏歌单、私有/无权限及删除后的歌单缺少真实样本，不能宣称所有协议已确认。本轮没有实现音乐库业务页面。

## 执行范围

用户授权核对只读接口后，在开发模拟器内复用现有加密会话，通过仅监听 `127.0.0.1:13002` 的临时工具调用上游原模块与 `createRequest`，使用普通版配置。没有发送短信、登录续期、获取播放地址、播放歌曲、提交反馈或修改云端歌单。

认证及完整响应仅在应用/临时进程内存中使用；输出限制为字段类型、数量、受限业务标志与布尔比较。不记录真实账号/歌单/歌曲 ID、名称、认证或完整响应。工具屏蔽上游日志，限制只读模块、超时及重定向；未经过通用 HTTP 缓存层。正式客户端仍须对歌单请求使用 `X-Apicache-Bypass: 1`，依据见 [首页缓存核对](HOME_API_CHECK.md#中转缓存核对与决定)。

共执行 21 次上游只读模块调用：

| 模块 | 次数 | 用途 |
| --- | ---: | --- |
| `user_playlist` | 4 | pagesize=2 的第 1、2、3 页；pagesize=30 的完整元数据复核 |
| `search` | 3 | 两次公开歌单搜索（工具补充统计后重建内存引用）；一次公开歌曲搜索 |
| `playlist_detail` | 3 | 公开歌单、系统喜欢、明确无效参数 |
| `playlist_track_all_new` | 7 | 系统喜欢首批两次、相邻页、尾页和结束页；自建歌单；真实空歌单 |
| `playlist_track_all` | 4 | 公开歌单首批、相邻页、尾页和结束页 |

21 次均收到封装 HTTP 200、数字 `status=1/error_code=0`；其中无效详情参数并不是成功取得歌单，见下文。没有自动重试请求。未下载整份 446 首或 165 首歌单，分页验证采用首批、相邻页与尾部抽查。

另有一次“使用私有喜欢歌曲名搜索”的拟议请求被自动审批拒绝，理由为向外部服务披露私有歌单信息；该请求未执行。改为使用公开搜索歌单中的公开歌曲核对曲长，没有绕过拒绝继续使用私有查询。

## 1. 用户歌单与系统喜欢

`/user/playlist` → 网关 POST `/v7/get_all_list`，路由 `cloudlist.service.kugou.com`。模块固定 `type=2`，它是获取列表的请求参数，不能与单个歌单的 type 混淆。

正文为 `data` 对象，歌单位于 `data.info`。本账号 `list_count=4`、`collect_count=0`、`album_count=0`；pagesize=2 时，第 1/2 页各 2 条，第 3 页 `info=null`，计数仍为 4。完整复核返回 4 条。

| 返回顺序 | type | is_def | count | 核对结论 |
| --- | ---: | --- | ---: | --- |
| 0 | 0 | 1 | 0 | 系统默认列表，名称匹配“默认收藏”，真实空歌单；不能当成“我喜欢” |
| 1 | 0 | 2 | 446 | 系统喜欢；名称检查和用户官方客户端数量反馈一致 |
| 2 | 0 | 缺失 | 326 | 普通自建歌单，数量与用户反馈一致 |
| 3 | 0 | 缺失 | 15 | 普通自建歌单，数量与用户反馈的 download 歌单一致 |

四条 `list_create_userid` 均与当前账号匹配，但 **is_mine 全部为 0**，不能据此把它们归为他人歌单。四条均有数字 `listid`、字符串 `global_collection_id`（collection_ 格式）、来源字段 `list_create_listid/list_create_gid`，这些标识保持各自语义。

其他已见字段：`name/pic/intro` 为字符串，`type/is_def/count/list_ver/is_pri/is_edit/is_del/source` 为数字，其中 `is_def` 只在两个系统列表出现。四条 `is_pri=0`，未覆盖私有歌单。

实现决定：

- 当前协议下用 `is_def=2` 识别系统喜欢，固定入口引用返回的真实 listid；不按名称或硬编码 ID 识别。缺失/冲突时显示未能识别，不猜测。
- 当前账号支持 `type=0` 自建分类；上游文档称 1 为收藏，但本账号没有 type=1 样本，不能声称收藏分类已实测。
- 不排除 count=0 的真实歌单。系统默认列表不重复映射为喜欢入口；其他已识别歌单保持可浏览。
- collect_count=0 的样本不足以判断 list_count 是否包含收藏，也不足以证明 list_count+collect_count 是分页总量。收藏非空时需补核对，不能先写死通用总数公式。

## 2. 云端歌曲页

`/playlist/track/all/new` → 网关 POST `/v4/get_list_all_file_v3`，同一 cloudlist 路由。使用返回的 **listid** 和 **type=0**；显式 page/pagesize。本次歌曲页均使用 pagesize=2。

响应 `data` 中有数字 `page/pagesize/count/list_ver/listid/userid`，字符串 `snap/cursor`，歌曲为 `info` 数组或 null。

| 场景 | 结果 |
| --- | --- |
| 系统喜欢第 1、2 页 | 各 2 首，count=446，fileid 无跨页重叠，list_ver 相同 |
| 系统喜欢第 223 页 | 2 首，count=446 |
| 系统喜欢第 224 页 | info=null，count=446，仍是业务成功 |
| 326 首自建歌单第 1 页 | 2 首，count=326 |
| 系统默认空列表第 1 页 | info=null，count=0，业务成功 |

第一页/第二页的 snap 和 cursor 相同，但未确认它们是否为空、是否为快照或游标，模块也不转发这两个入参，不能自行实现基于它们的翻页。使用已验证的 page/pagesize，并检查 page/count/list_ver 和累计完整性；count 或版本变化时停止整表准备并提示刷新，不能把混合页冒充同一完整列表。

`info=null` 只在声明总数为零或所请求位置已经到达总数时可视为已观察到的正常空页；未到总数却返回空页需报未取齐。不能将缺失字段、业务错误或畸形对象统一转换为空列表。

## 3. 公开搜索歌单与详情

公开 `/search?type=special` 返回 `data.lists[]`，其中 `specialid` 是数字，**gid 是字符串**。实测将 gid 传给 `/playlist/detail` 的 ids 可取得详情；传给 `/playlist/track/all` 的 id 可取得歌曲。歌曲首批返回的 `list_info.global_collection_id` 与搜索 gid 相等。

当前 Android `SearchResult` 只保存 specialid，尚未保存 gid。步骤 3 接详情时必须补充真实歌单引用，不将 specialid 强行当 global_collection_id。

`/playlist/detail` 正常 `data` 是数组，本次公开和系统喜欢各返回一条。系统喜欢详情为 `is_def=2/count=446/type=0/code=1/status=1`，global_collection_id 与列表相同；未测试私有详情的读取权限。

**异常结构实测**：用明确无效字符串 `__invalid_readonly_probe__` 作为 ids，外层仍返回 `status=1/error_code=0`，但 `data={}`。这是未取得有效详情，不是 count=0 的空歌单。后续必须校验数组、所请求标识及条目内容，不能只看 HTTP 或外层业务码。该样本不是已删除或无权限歌单的真实错误样本，不能据此细分错误原因。

公开歌曲 `/playlist/track/all`：模块把 page 转为 `(page-1)*pagesize` 的 begin_idx，正文 `data` 含数字 `begin_idx/pagesize/count`、`songs` 数组与 `list_info` 对象。

- count=165，pagesize=2；第 1/2 页各 2 首，fileid 无跨页重叠。
- 第 83 页 1 首，第 84 页 songs=[]，count 仍为 165。
- 第一页 list_info 为完整对象，后续页为 `{}`，后续页不能清空已加载的详情。
- 公开歌曲页后续没有已验证的列表版本字段；总数稳定不能证明所有页面来自同一快照。整表播放仍需完整性检查，不能承诺读取中云端变更的强一致性。

## 4. 歌曲字段映射依据

云端 info 和公开 songs 均观察到以下字段；两个接口应分别校验外层结构，再映射到领域模型，不能复用整个原始响应。

| 字段 | 观察类型 | 后续用途与限制 |
| --- | --- | --- |
| hash | string | 首批均为 32 位十六进制；保留原歌曲标识 |
| name | string | 返回的展示名称；未证明永远是纯歌名，不靠拆分名字推导歌手 |
| singerinfo[] | array，元素含 name:string、id:number | 完整歌手数组，优先于解析 name |
| album_id | string | 保留；首批均与 albuminfo.id 的数字值相等 |
| albuminfo | object，含 name:string、id:number | 专辑元数据 |
| mixsongid | number | 独立保留；公开同 hash 样本与搜索 MixSongID 相等 |
| album_audio_id | 首批未出现 | 不伪造该字段；mixsongid 用于播放参数的适配沿用现有搜索依据，实际歌单点播留步骤 4 验收 |
| audio_id | number | 与 mixsongid 保持分开，不互相替代 |
| fileid | number | 歌单条目标识候选；首批及相邻页唯一，但真实重复歌曲条目语义尚无样本 |
| sort / fsort | number；公开未见 fsort | 云端首批 sort 为 445/444 范围，公开首批为 0/1；保持接口数组顺序，不自行按 sort 升序重排 |
| cover | string | 封面候选，实际图片加载与空串处理随界面实现核对 |
| timelen | number | 公开单位确认毫秒；云端首批为 250409–283167，见下文证据限制 |

公开首曲同 hash 搜索匹配到 1 条：`timelen=92000`、搜索 `Duration=92`，album_id 与 mixsongid 也分别匹配。因此公开歌曲 timelen 直接作为毫秒，不再乘 1000。

云端 timelen 呈毫秒量级，但本轮未把私有歌单歌曲发送去做同 hash 搜索交叉验证；不能把公开样本说成云端独立实测。后续若按同名字段毫秒解释，须保留此证据限制，并在用户实际点播时对照播放器时长，不用估计时长限制播放权限。

云端有 `hash_exist/media_privilege/media_pay_type/media_fail_process`，公开有 `privilege/download[]/relate_goods[]`。这些数字存在不代表已确认所有权限枚举；列表不能据未核实值宣称完整播放或直接丢弃歌曲，实际播放权限由现有播放地址解析决定。

## 5. 请求适配与剩余边界

精确读取：`module/user_playlist.js`、`playlist_detail.js`、`playlist_track_all.js`、`playlist_track_all_new.js`、`search.js`，`util/request.js`、`server.js`、`docs/README.md` 和 `interface.d.ts` 对应段落。

用原模块及捕获配置的模拟 request 做了无网络断言：user_playlist 转发 page/pagesize，固定 type=2；新版歌曲页转发 listid/type/page/pagesize，默认 type=0/pagesize=300；公开页 page=3/pagesize=2 转为 begin_idx=4；详情 ids 拆为 global_collection_id 数组。全部通过。文档所述新版默认 pagesize=30 与源码不一致，客户端必须显式传值。

正式 Android 请求可按既有服务约定用 POST body 承载歌单 ID、分页等参数，Cookie 仍由已有客户端管理；本轮调用模块直接绕开 HTTP 服务层，没有再次用真实账号验证通用 POST/bypass 路由。该约定的已有源码与缓存证据见 HOME_API_CHECK。上游负责签名和内部传输，不能把本轮检查称为已完成 Kotlin 直连。

| 未覆盖项 | 原因 / 下一步 |
| --- | --- |
| type=1 收藏歌单、list_count 与 collect_count 的关系 | 当前账号 collect_count=0，未为核对创建收藏；保留未验证状态，后续有真实样本再确认 |
| 私有、无权限、已删除、失效认证 | 当前歌单均 is_pri=0，会话有效；不主动退出或伪造账号；错误保留通用失败，不能伪装为空 |
| 真实重复条目、缺 hash、不可播放条目 | 样本未覆盖，不能从首批唯一性推导全局 hash 去重规则；实现时用明确标注的模拟异常测试 |
| 整表读取期间发生云端修改 | 未制造写操作，依据版本/总数做防御检查，不能承诺服务端快照能力 |
| 云端曲长独立交叉验证、实际播放及封面加载 | 留给用户主动播放及步骤 4 设备验收，本轮不调用音频服务 |

## 清理与验证

临时 instrumentation APK 构建成功并运行，只返回 session_ready；仅安装临时测试包，没有替换或卸载主应用。临时测试包、转发、服务进程、源码及 Gradle runner 配置已移除，会话保留。上游未修改；原有未跟踪 `package-lock.json` 保留。

脱敏观察摘要见 [cloud-library-observation.json](samples/cloud-library-observation.json)，它是结构观察记录，不是可直接喂给解析器的真实 API 响应。

移除工具后，JDK 17 下 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline` 成功。正常应用源码无差异，构建与单元测试任务为 UP-TO-DATE，已有测试报告为 100 项、0 失败/错误，不声称本轮新增或重新执行 100 项测试。lint 本轮执行完成，0 错误、7 条警告。脱敏 JSON 解析与 `git diff --check` 通过。最终仅文档变更，没有安装新主应用包或新增依赖真实账号的自动化测试。
