# 04 · 事件与工具体检

> 板块状态：0/26 · 已知问题：无 · 续跑指引：从第一个未勾选项开始，看→修→确认→勾选
> 事件类型白名单：exam/trip/appointment/health/general；只读：birthday/anniversary（utils/events.py）

- [ ] **4.1 事件 CRUD 契约**
      · 查：POST/GET /api/events 基本契约
      · 取证：`curl -s http://127.0.0.1:8000/api/events -H "Authorization: Bearer $TOKEN"` 看结构；POST 一条测试事件（含 type='general'、日期=明天）再 GET
      · 判据：创建/读取一致；时间字段格式正确
      · 严重度：P0
      · 结果：
- [ ] **4.2 事件类型校验**
      · 查：非白名单 type 被拒（exam/trip/appointment/health/general）
      · 取证：POST type='foobar' → 预期 4xx；代码核验 _EVENT_TYPES
      · 判据：非法类型被拒、错误信息友好
      · 严重度：P1
      · 结果：
- [ ] **4.3 只读类型保护**
      · 查：birthday/anniversary 不可通过 API 创建/删除（只读）
      · 取证：POST type='birthday' → 预期拒绝；代码核验 _READONLY_EVENT_TYPES
      · 判据：只读类型被保护
      · 严重度：P1
      · 结果：
- [ ] **4.4 软删墓碑**
      · 查：事件删除为软删（dedupe 唯一索引谓词 status≠'deleted'）
      · 取证：删除测试事件 → 查库 events 行 status；同 dedupe_key 可重建
      · 判据：删除后 status='deleted'（非物理删），重建不冲突
      · 严重度：P1
      · 结果：
- [ ] **4.5 事件去重**
      · 查：同 dedupe_key 活跃行唯一（不重复创建）
      · 取证：POST 同一事件两次 → 第二次被去重或返回已存在
      · 判据：无重复活跃行
      · 严重度：P1
      · 结果：
- [ ] **4.6 日历可见类型**
      · 查：_CALENDAR_VISIBLE_TYPES 含白名单 + 只读两类
      · 取证：代码核验常量；前端 CalendarPanel 请求的 events 覆盖
      · 判据：常量正确、日历能显示全部应显示类型
      · 严重度：P2
      · 结果：
- [ ] **4.7 事件完成/更新**
      · 查：POST /events/{id}（完成/更新）契约
      · 取证：对测试事件 POST → 查库状态变化
      · 判据：状态流转合理（active→done 类）
      · 严重度：P1
      · 结果：
- [ ] **4.8 事件删除**
      · 查：DELETE /events/{id} 软删 + 幂等
      · 取证：删除测试事件 → 再删 → 均成功不炸
      · 判据：删除幂等
      · 严重度：P2
      · 结果：
- [ ] **4.9 周期记录**
      · 查：cycle_records 读写（log_period/get_period_status）
      · 取证：查库 `SELECT COUNT(*) FROM cycle_records`；API/工具触发一次读
      · 判据：读面正常；写面（若产生数据）与前端一致
      · 严重度：P1
      · 结果：
- [ ] **4.10 周期推算事件**
      · 查：source='cycle' 的推算事件（经期提醒类）生成逻辑
      · 取证：代码核验 events 创建处 source 字段；查库 events 中 source='cycle' 行
      · 判据：推算合理、不重复生成
      · 严重度：P2
      · 结果：
- [ ] **4.11 日记工具**
      · 查：write_diary/read_diary 工具真实可用
      · 取证：对话触发写日记 → 查 diaries 表 → 触发读回
      · 判据：写读一致
      · 严重度：P1
      · 结果：
- [ ] **4.12 塔罗**
      · 查：tarot 工具输出结构（卡牌/解读）正常
      · 取证：对话触发一次塔罗 → 观察回复
      · 判据：输出结构化、无报错
      · 严重度：P2
      · 结果：
- [ ] **4.13 天气工具**
      · 查：get_weather 真实 key 下返回实时天气
      · 取证：对话「今天香港天气」→ 观察
      · 判据：返回真实天气数据（非编造），LLM 转述自然
      · 严重度：P1
      · 结果：
- [ ] **4.14 地图搜索**
      · 查：search_nearby/fuzzy_search_place（高德）真实可用
      · 取证：对话「附近有什么咖啡店」→ 观察结果条数与格式
      · 判据：有真实结果、POI 转述口语化
      · 严重度：P1
      · 结果：
- [ ] **4.15 GPS 与隐私**
      · 查：GPS opt-out 生效（不开 GPS 则相关功能降级/无行）
      · 取证：查库 GPS 相关表/设置；代码核验 opt-out 分支（对照 test_gps_optout 语义）
      · 判据：opt-out 时无 GPS 数据写入、功能优雅降级
      · 严重度：P1
      · 结果：
- [ ] **4.16 出行路线**
      · 查：get_transit_route/get_cycling_route 契约
      · 取证：对话触发一次路线查询 → 观察
      · 判据：返回合理或友好降级（无 500）
      · 严重度：P2
      · 结果：
- [ ] **4.17 网络搜索**
      · 查：web_search（Tavily）真实可用
      · 取证：对话问一个需要搜索的问题 → 观察工具调用与转述
      · 判据：搜索触发、结果转述合理
      · 严重度：P1
      · 结果：
- [ ] **4.18 记忆工具面**
      · 查：remember/recall/update/delete/list/export 全工具注册与可用
      · 取证：`backend/.venv/Scripts/python -m scripts.verify_tools`（scripts/verify_tools.py 一致性校验）
      · 判据：工具注册表与实现一致（verify_tools 输出无缺失）
      · 严重度：P1
      · 结果：
- [ ] **4.19 工具错误转述**
      · 查：工具抛错时 LLM 优雅转述（不裸露技术错误）
      · 取证：触发一个会失败的场景（如无 key 的工具/边界输入）→ 观察回复
      · 判据：回复为自然语言说明，非堆栈/英文报错
      · 严重度：P1
      · 结果：
- [ ] **4.20 工具超时**
      · 查：单工具调用有超时（不无限挂起）
      · 取证：代码核验工具执行超时配置
      · 判据：有超时上限、超时后对话继续
      · 严重度：P2
      · 结果：
- [ ] **4.21 事件提醒联动**
      · 查：active_events 被心跳/主动消息消费（3.17 交叉）
      · 取证：创建近期事件 → 触发心跳/主动消息 → 看是否提及
      · 判据：事件被合理提及
      · 严重度：P2
      · 结果：
- [ ] **4.22 日历前端数据流**
      · 查：CalendarPanel ↔ /api/events 数据流一致
      · 取证：E2E 或浏览器打开日历面板，创建/编辑/完成事件，观察前端状态与后端同步
      · 判据：前端操作均反映到后端、刷新不丢
      · 严重度：P1
      · 结果：
- [ ] **4.23 状态路由**
      · 查：/api/status 契约（mood/memory_count 等）
      · 取证：`curl -s http://127.0.0.1:8000/api/status -H "Authorization: Bearer $TOKEN"` 看结构
      · 判据：结构与前端消费一致、数据实时
      · 严重度：P1
      · 结果：
- [ ] **4.24 配置路由**
      · 查：/api/config GET/POST（token 配置、主题）契约与认证
      · 取证：GET 看结构；POST 需认证
      · 判据：结构合理、写操作有认证保护
      · 严重度：P1
      · 结果：
- [ ] **4.25 健康端点**
      · 查：/api/health 无认证可访问 + 载荷合理
      · 取证：`curl -s http://127.0.0.1:8000/api/health`（无 token）
      · 判据：200 ok，含 status/name 等，无敏感信息
      · 严重度：P1
      · 结果：
- [ ] **4.26 会话管理**
      · 查：/api/sessions GET 与 DELETE 契约（会话列表/清理）
      · 取证：GET 看列表；DELETE 测试会话（check-1 可删）
      · 判据：列表完整、删除生效且不影响对话主流程
      · 严重度：P1
      · 结果：
