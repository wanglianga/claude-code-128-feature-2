# 牙科义齿加工订单取模返工与配送服务

基于 **Java 17 + Spring Boot 3.3 + PostgreSQL** 的义齿加工协同服务：诊所提交烤瓷冠、全瓷冠、活动义齿、隐形义齿、种植上部修复订单，技工所按牙位推进 模型接收→扫描设计→医生确认→排牙→烧结→抛光→质检→配送 八道工序，并把诊所医生、技工、质检、配送、客服、财务连接到同一颗牙/同一副义齿上，覆盖取模异常、**试戴不密合返工（咬合高 / 边缘不密合）**、返工方案诊所确认、返工责任与月结费用调整、加急生产、配送签收、订单结算与**诊所月结三方同步（技工所同步→诊所确认→财务确认，含月结争议）**全流程。

## 目录

- [快速开始](#快速开始)
  - [方式一：Docker Compose 一键启动（推荐）](#方式一docker-compose-一键启动推荐)
  - [方式二：本机已有 PostgreSQL](#方式二本机已有-postgresql)
- [接口文档 Swagger UI](#接口文档-swagger-ui)
- [测试账号](#测试账号)
- [验证方式](#验证方式)
- [API 一览](#api-一览)
- [核心业务规则](#核心业务规则)

## 快速开始

### 方式一：Docker Compose 一键启动（推荐）

需要本机已安装 Docker 与 Docker Compose。

```bash
docker compose up --build
# 首次启动会构建应用镜像、启动 PostgreSQL 16 并自动建表+写入演示数据
```

- 服务地址：http://localhost:8080
- 健康检查：http://localhost:8080/api/health
- PostgreSQL 映射到宿主机 `5433`（库名/用户/密码均为 `dentallab/postgres/postgres`，数据持久化在命名卷 `dentallab-pgdata`）

停止并保留数据：`docker compose stop`；连同数据卷一起清除：`docker compose down -v`。

> 交付物包含多阶段 `Dockerfile`（Maven 构建 + JRE 运行）、`docker-compose.yml`（app + db 编排、数据库健康检查）与 `docker-entrypoint.sh`（等待数据库就绪）。

### 方式二：本机已有 PostgreSQL

1）准备数据库

方式 A：使用已有实例（创建数据库即可）

```bash
psql -U postgres -c "CREATE DATABASE dentallab;"
psql -U postgres -c "CREATE DATABASE dentallab_test;"   # 集成测试使用
```

方式 B：本机没有可用实例时，用 PostgreSQL 自带工具起一个用户级实例（无需 root）

```bash
export PATH=/usr/lib/postgresql/15/bin:$PATH      # 按本机实际版本调整
initdb -D ~/pgdata -U postgres --auth=trust --encoding=UTF8
pg_ctl -D ~/pgdata -o "-p 5433 -k /tmp" -l ~/pgdata.log start
psql -h 127.0.0.1 -p 5433 -U postgres -c "CREATE DATABASE dentallab;"
psql -h 127.0.0.1 -p 5433 -U postgres -c "CREATE DATABASE dentallab_test;"
```

2）配置数据库连接（默认值对应方式 B；Docker 方式由 compose 注入，无需设置）

| 环境变量 | 默认值 |
|---|---|
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5433/dentallab` |
| `DB_USER` | `postgres` |
| `DB_PASS` | `postgres` |
| `TEST_DB_URL` | `jdbc:postgresql://127.0.0.1:5433/dentallab_test` |
| `SERVER_PORT` | `8080` |

3）启动服务

```bash
mvn spring-boot:run
# 或
mvn package -DskipTests && java -jar target/denture-order-service-1.0.0.jar
```

首次启动自动建表并写入演示数据（诊所、账号、材料批次、价格配置）。健康检查：`curl http://localhost:8080/api/health`

4）运行测试与全流程验证

```bash
mvn test          # 9 个端到端集成测试（真实 PostgreSQL，dentallab_test 库）
./verify.sh       # 需服务已启动；curl+jq 走通全流程并断言 65 项检查
```

## 接口文档 Swagger UI

服务启动后可直接打开（纯 API 服务，无需额外前端）：

- Swagger UI：http://localhost:8080/swagger-ui.html
- OpenAPI 3.x JSON：http://localhost:8080/v3/api-docs

在 Swagger UI 右上角 **Authorize** 填入 `/api/auth/login` 返回的 token（直接填 token 字符串，无需 Bearer 前缀），即可在线调试所有接口。

## 测试账号

所有接口（除 `/api/auth/login`、`/api/health`、Swagger 文档路径）都需要请求头 `X-Auth-Token: <登录返回的token>`。

| 用户名 | 密码 | 角色 | 说明 |
|---|---|---|---|
| `doctor1` | `doctor123` | 诊所医生（晨曦口腔诊所） | 下单、确认设计、批注、记录试戴（咬合高/边缘不密合+照片）、确认/驳回返工 |
| `doctor2` | `doctor123` | 诊所医生（博雅口腔门诊） | 第二家诊所 |
| `clinicadmin1` | `admin123` | 诊所管理员（晨曦） | 签收、确认/驳回返工、复核返工、月结确认与争议 |
| `receiver` | `lab123` | 技工所前台 | 接单、模型接收、发货、提交返工方案、同步月结 |
| `labadmin` | `lab123` | 技工所管理员 | 提交返工方案、标记返工原因、判定责任、库存、处理月结争议 |
| `tech1` / `tech2` | `tech123` | 技工 | 扫描设计（生成设计版本）、排牙、烧结、抛光 |
| `qc1` | `qc123` | 质检员 | 质检工序（返工牙位须先标记原因才能过质检） |
| `courier1` | `courier123` | 配送员 | 配送 |
| `service1` | `service123` | 客服 | 异常处理、提交返工方案、处理月结争议 |
| `finance1` | `finance123` | 财务 | 结算、生成月结单、财务确认 |

> 演示环境使用明文密码与长期令牌，生产环境应替换为密码散列与标准认证。

## 验证方式

本项目实际执行过以下验证（结论均来自真实运行输出）：

1. **`mvn test` — 9 个集成测试全部通过**（Spring Boot 随机端口 + 真实 PostgreSQL `dentallab_test` 库）：
   - 全角色登录、错误密码 401、无令牌 401、越权 403；OpenAPI 文档与 Swagger UI 可匿名访问；
   - 下单→接单→八道工序（材料扣减、设计版本 v1、照片、预计完成时间、工序顺序校验）；
   - 发货→完好签收→自动试戴安排；牙位 11 密合 / 牙位 12 边缘不密合（不填问题类型被拒）→自动异常+自动返工，返工关联设计版本、医生备注、试戴照片；
   - 返工方案未确认不能重做；技工所提交方案（48h）→诊所驳回→修订（24h）→诊所确认；
   - 重做质检前未标记原因被拦截；标记“加工问题”→责任技工所、扣减 200；返工重设计生成设计版本 v2 并关联返工单；重做后返工单自动关闭、单牙位重发、试戴密合、订单完成；
   - 结算金额断言（基础费 1000、难度系数 1.30、技工所承担返工扣减 200、应付 1100）；
   - 月结：未同步/未复核/争议中确认被拦截；技工所同步→诊所发起争议（关联返工）→技工所处理后重新同步→复核返工→诊所确认→财务确认；
   - 第二场景加急单 + 延误签收：加急费 90、延误扣罚 15；**咬合高返工标记“医生取模问题”→诊所承担 100**，应付 475（300+90+100−15）。
2. **`./verify.sh` — 65 项断言全部通过**（针对 8080 端口全新数据库运行中的服务，覆盖登录鉴权、Swagger 文档、下单、口扫文件、八道工序、设计版本、库存扣减、发货签收、试戴密合/边缘不密合/咬合高、返工方案-驳回-确认、加工/取模原因标记与费用责任、返工重做与重发、结算拦截与金额、月结同步/争议/财务确认、按牙位看板、技工绩效、加急与配送延误场景）。

> `verify.sh` 面向**全新数据库**运行（月结单按“诊所+账期”唯一，重复运行需重建库：`docker compose down -v && docker compose up`，或删除并重建 `dentallab` 库后重启服务）。

## API 一览

统一响应：`{"success": true, "data": ...}`；错误：`{"success": false, "error": "..."}`。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 登录，返回 token |
| GET | `/api/auth/me` | 登录 | 当前账号 |
| GET | `/v3/api-docs` `/swagger-ui.html` | 公开 | OpenAPI 3.x 文档 / Swagger UI |
| POST | `/api/orders` | 诊所医生 | 提交订单（患者匿名编号、牙位、取模方式、咬合关系、色号、备注、预计试戴日期、材料要求、难度） |
| POST | `/api/orders/{id}/accept` | 前台/管理员 | 技工所接单 |
| POST | `/api/orders/{id}/rush` | 诊所/客服/管理员 | 患者加急（留痕异常事件，结算计加急费） |
| POST | `/api/orders/{id}/scan-files` | 诊所/技工所 | 登记数字口扫文件 |
| GET | `/api/orders` `/api/orders/{id}` | 登录 | 订单列表（诊所仅见本诊所）/ 详情聚合（含设计版本） |
| POST | `/api/orders/{id}/items/{itemId}/stages` | 技工/质检/前台 | 登记工序（操作人、材料批次与用量、照片、预计完成时间；扫描设计生成设计版本） |
| POST | `/api/orders/{id}/items/{itemId}/confirm` | 诊所医生 | 医生确认设计（返工重做需返工方案已确认） |
| POST | `/api/orders/{id}/items/{itemId}/annotations` | 诊所医生 | 在线批注（设计冻结后批注自动登记“医生临时改设计”异常） |
| POST | `/api/annotations/{id}/resolve` | 技工所 | 标记批注已处理 |
| GET | `/api/orders/{id}/tooth-board` | 登录 | **按牙位看板**：设计版本、制作、质检、配送、试戴、返工责任与原因 |
| POST | `/api/orders/{id}/exceptions` | 登录 | 上报异常（取模气泡/色号争议/改设计/排期拥堵/试戴不密合/加急/配送延误/返工责任不清） |
| POST | `/api/exceptions/{id}/handle` | 客服/管理员 | 处理异常 |
| GET | `/api/exceptions/open` | 登录 | 待处理异常工作台 |
| POST | `/api/orders/{id}/items/{itemId}/reworks` | 多角色 | 对单个牙位发起返工（试戴不密合由系统自动创建） |
| POST | `/api/reworks/{id}/plan` | 技工所/客服 | **提交/修订返工方案**：返工时限(小时)、建议责任、返工成本、是否收费、技工操作 |
| POST | `/api/reworks/{id}/clinic-confirm` | 诊所 | **诊所确认返工方案**（时限/责任/收费落定，牙位方可返工重做） |
| POST | `/api/reworks/{id}/clinic-reject` | 诊所 | 诊所驳回返工方案（需驳回原因），技工所修订后重提 |
| POST | `/api/reworks/{id}/cause` | 技工所/质检/管理员 | 返工确认后标记原因：**DESIGN 设计问题 / PROCESSING 加工问题 / IMPRESSION 医生取模问题** |
| POST | `/api/reworks/{id}/assign-responsibility` | 管理员/客服 | 直接判定返工责任与成本（争议裁决） |
| POST | `/api/reworks/{id}/clinic-review` | 诊所 | 复核返工（月结前置条件） |
| GET | `/api/reworks/awaiting-plan` `/api/reworks/awaiting-confirm` `/api/reworks/unreviewed` | 对应方 | 技工所待方案 / 诊所待确认 / 诊所待复核工作台 |
| GET/POST | `/api/inventory/materials` `/api/inventory/materials/{id}/batches` | 技工所 | 材料档案、批次入库、库存总览（含低库存预警） |
| POST | `/api/orders/{id}/shipments` | 前台/配送 | 发货（可只发指定牙位，支持返工后重发） |
| POST | `/api/shipments/{id}/sign` | 诊所/配送 | 签收：完好/延误/破损，直接影响试戴安排 |
| POST | `/api/orders/{id}/try-ins` | 诊所医生 | 记录试戴：`FIT` 密合 / `MISFIT`+`issueType` 边缘不密合 / `HIGH_BITE` 咬合高；医生备注、试戴照片、体验评分 |
| POST | `/api/orders/{id}/settlement` | 财务/管理员 | 生成订单结算单 |
| GET | `/api/orders/{id}/settlement` | 登录 | 查看结算单 |
| POST | `/api/statements/generate` | 财务/管理员 | 生成诊所月结单（含返工责任调整金额） |
| POST | `/api/statements/{id}/sync` | 技工所/财务 | **技工所把月结单同步给诊所** |
| POST | `/api/statements/{id}/confirm` | 诊所管理员 | 诊所确认月结（账期内返工须全部复核） |
| POST | `/api/statements/{id}/finance-confirm` | 财务/管理员 | **财务端确认月结**（诊所确认之后） |
| POST | `/api/statements/{id}/dispute` | 诊所 | **发起月结争议**（可关联返工单，争议同步记录） |
| POST | `/api/statements/disputes/{disputeId}/resolve` | 技工所/客服/财务 | 处理月结争议（全部解决后自动重新同步诊所） |
| GET | `/api/statements` `/api/statements/{id}` | 登录 | 月结单列表 / 详情（含争议记录与返工调整明细） |
| GET | `/api/performance/technicians` | 管理员/财务 | 技工绩效（完成工序数、准时率、返工牙位数、材料用量） |

## 核心业务规则

- **按牙位流转**：每颗牙独立走八道工序（`MODEL_RECEIVING → SCAN_DESIGN → DOCTOR_CONFIRM → TEETH_ARRANGEMENT → SINTERING → POLISHING → QC → DELIVERY`），工序必须按序登记；每道工序记录操作人、材料批次与用量（实时扣减库存，不足报错）、照片、预计完成时间。返工只打回对应牙位，不影响整单其他牙位。
- **设计版本**：扫描设计完成即生成设计版本（首版 v1）；返工重新设计时版本递增（v2…）并关联对应返工单；**试戴不密合自动创建的返工单关联到该牙位当前（问题）设计版本**，按牙位看板可追溯每个版本。
- **医生确认与批注**：扫描设计后必须由本诊所医生确认才能继续生产；设计冻结（确认）后的批注自动登记“医生临时改设计”异常。
- **试戴不密合返工**：医生试戴可反馈 `MISFIT`（需 `issueType=EDGE_GAP/BOTH` 边缘不密合）或 `HIGH_BITE` 咬合高，附**医生备注与试戴照片**；系统自动登记“试戴不密合”异常并对该牙位创建返工单（待方案），返工单关联**设计版本、医生备注、试戴照片与试戴记录**。配送破损签收同样对本批牙位自动建返工单。
- **返工方案与诊所确认**：技工所就返工单提交**返工时限（小时）、建议责任（诊所/技工所/共担）、返工成本、是否向诊所收费、技工操作说明**；状态变为待诊所确认。诊所可**确认**（责任与收费落定，牙位方可重做）或**驳回**（填原因，技工所修订后重新提交）。返工方案未经诊所确认，牙位不能登记返工工序；责任未落定的返工不允许结算。
- **返工原因标记与月结费用调整**：返工牙位**重做质检前**，技工所必须标记原因：**设计问题 / 加工问题（=技工所责任）或医生取模问题（=诊所责任）**，系统据此落定费用承担；牙位重新通过质检后返工单自动关闭。月结单按账期汇总返工“诊所承担（加收）/技工所承担（扣减）”调整金额。
- **结算公式**：`应付 = 基础费(单价×牙位数) × 难度系数(1+0.15×(难度-1)) + 加急费(基础费×难度系数×加急费率) + 诊所承担返工费 − 技工所承担返工费 − 配送扣罚(延误5%/破损10%)`，最低为 0。各类型单价与加急费率存于 `price_config`。
- **月结三方同步与争议**：财务生成（按订单完成时间归属账期，账期内订单须已结算）→ **技工所同步给诊所** → 诊所确认（账期内返工须全部复核，否则被拒）→ **财务端确认**。同步后诊所可发起**月结争议**（可关联返工单，月结单进入争议中并同步记录争议），技工所/财务处理、全部争议解决后自动重新同步诊所，诊所再次确认；终态不可重复确认。
- **技工绩效**：按技工统计完成工序数、准时率（对比预计完成时间）、经手牙位的返工数与材料用量。

## 工程结构

```
src/main/java/com/dentallab/
├── config/    # 拦截器、异常处理、OpenAPI 配置、种子数据
├── model/     # 实体（含 DesignVersion、StatementDispute）与枚举
│              #  （MisfitType 咬合高/边缘不密合、ReworkCause 设计/加工/取模、ReworkStatus 返工方案状态机）
├── repo/      # Spring Data JPA 仓库
├── service/   # 业务服务（认证/订单/生产/异常/返工/库存/配送/试戴/结算/月结/绩效）
└── web/       # REST 控制器 + 请求解析辅助
src/test/java/com/dentallab/DentalLabFlowTest.java   # 9 个端到端集成测试
verify.sh      # 65 项断言的全流程 curl 验证脚本
Dockerfile / docker-compose.yml / docker-entrypoint.sh   # 一键启动（app + PostgreSQL 16）
```
