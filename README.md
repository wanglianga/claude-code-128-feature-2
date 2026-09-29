# 牙科义齿加工订单取模返工与配送服务

基于 **Java 17 + Spring Boot 3.3 + PostgreSQL** 的义齿加工协同服务：诊所提交烤瓷冠、全瓷冠、活动义齿、隐形义齿、种植上部修复订单，技工所按牙位推进 模型接收→扫描设计→医生确认→排牙→烧结→抛光→质检→配送 八道工序，并把诊所医生、技工、质检、配送、客服、财务连接到同一颗牙/同一副义齿上，覆盖取模异常、**试戴不密合返工（咬合高/边缘不密合、诊所确认时限与是否收费、技工所标记设计/加工/取模原因、按责任调整费用）**、加急生产、配送签收、试戴体验、订单结算与**诊所月结（技工所同步、诊所确认、月结争议、财务端确认）**全流程。

## 目录

- [快速开始](#快速开始)
- [测试账号](#测试账号)
- [验证方式](#验证方式)
- [API 一览](#api-一览)
- [核心业务规则](#核心业务规则)

## 快速开始

### 方式 A：Docker Compose 一键启动（推荐）

```bash
docker compose up -d --build
# 服务：     http://localhost:8080/api/health
# Swagger：  http://localhost:8080/swagger-ui.html
# OpenAPI：  http://localhost:8080/v3/api-docs
```

自动启动 PostgreSQL 15（数据持久化到命名卷 `pgdata`）与应用；首次启动自动建表并写入演示数据。

### 方式 B：本地运行

#### 1. 准备 PostgreSQL

使用已有实例（创建数据库即可）：

```bash
psql -U postgres -c "CREATE DATABASE dentallab;"
psql -U postgres -c "CREATE DATABASE dentallab_test;"   # 集成测试使用
```

或本机没有可用实例时，用 PostgreSQL 自带工具起一个用户级实例（无需 root）：

```bash
export PATH=/usr/lib/postgresql/15/bin:$PATH      # 按本机实际版本调整
initdb -D ~/pgdata -U postgres --auth=trust --encoding=UTF8
pg_ctl -D ~/pgdata -o "-p 5433 -k /tmp" -l ~/pgdata.log start
psql -h 127.0.0.1 -p 5433 -U postgres -c "CREATE DATABASE dentallab;"
psql -h 127.0.0.1 -p 5433 -U postgres -c "CREATE DATABASE dentallab_test;"
```

#### 2. 配置数据库连接（默认值对应上面的用户级实例）

| 环境变量 | 默认值 |
|---|---|
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5433/dentallab` |
| `DB_USER` | `postgres` |
| `DB_PASS` | `postgres` |
| `TEST_DB_URL` | `jdbc:postgresql://127.0.0.1:5433/dentallab_test` |
| `SERVER_PORT` | `8080` |

#### 3. 启动服务

```bash
mvn spring-boot:run
# 或
mvn package -DskipTests && java -jar target/denture-order-service-1.0.0.jar
```

健康检查：`curl http://localhost:8080/api/health`

#### 4. 接口文档（OpenAPI 3 / Swagger UI）

- Swagger UI：http://localhost:8080/swagger-ui.html （页面右上角“Authorize”填入登录 token）
- OpenAPI JSON：http://localhost:8080/v3/api-docs

除 `/api/auth/login`、`/api/health` 外，所有 `/api/**` 接口都需要请求头 `X-Auth-Token: <登录返回的token>`。

#### 5. 运行测试与全流程验证

```bash
mvn test          # 10 个端到端集成测试（真实 PostgreSQL，dentallab_test 库）
mvn spring-boot:run            # 另开一个终端启动服务
./verify.sh       # curl+jq 走通全流程并断言 57 项检查
```

## 测试账号

| 用户名 | 密码 | 角色 | 说明 |
|---|---|---|---|
| `doctor1` | `doctor123` | 诊所医生（晨曦口腔诊所） | 下单、确认设计、批注、记录试戴（咬合高/边缘不密合+照片）、确认返工、提出月结争议 |
| `doctor2` | `doctor123` | 诊所医生（博雅口腔门诊） | 第二家诊所 |
| `clinicadmin1` | `admin123` | 诊所管理员（晨曦） | 签收、确认返工时限/是否收费、复核返工、确认月结、提出争议 |
| `receiver` | `lab123` | 技工所前台 | 接单、模型接收、发货 |
| `labadmin` | `lab123` | 技工所管理员 | 同步月结单、标记返工原因、判定返工责任、库存管理、处理争议 |
| `tech1` / `tech2` | `tech123` | 技工 | 扫描设计（生成设计版本）、排牙、烧结、抛光 |
| `qc1` | `qc123` | 质检员 | 质检工序、标记返工原因 |
| `courier1` | `courier123` | 配送员 | 配送 |
| `service1` | `service123` | 客服 | 异常处理、判定返工责任、处理月结争议 |
| `finance1` | `finance123` | 财务 | 结算、生成月结单、财务端最终确认 |

> 演示环境使用明文密码与长期令牌，生产环境应替换为密码散列与标准认证。

## 验证方式

本项目实际执行过以下验证（结论均来自 2026-09-29 的真实运行输出）：

1. **`mvn test` — 10 个集成测试全部通过**（Spring Boot 随机端口 + 真实 PostgreSQL `dentallab_test` 库，用户级实例 127.0.0.1:5433）：
   - 全角色登录、错误密码 401、无令牌 401、越权 403；
   - 下单→接单→八道工序（含材料扣减、照片、预计完成时间、工序顺序校验、扫描设计自动生成设计版本）；
   - 发货→完好签收→自动试戴安排；牙位 11 密合 / 牙位 12 **边缘不密合（misfitType=EDGE_MISFIT）**→自动异常+待诊所确认返工单（关联设计版本、医生备注、技工操作快照、试戴照片）；
   - **未诊所确认前牙位不能重做**；诊所确认返工时限 72h/免费返修后重做，重做产生设计版本 v2，返工单自动关闭、单牙位重发、订单完成；
   - **未标记返工原因不能判定责任**；标记“加工问题”+技工所责任 200；结算金额断言（基础费 1000、难度系数 1.30、技工所扣减 200、应付 1100）；
   - 月结完整状态机：生成→**技工所同步前诊所确认被拒**→同步→未复核确认被拒→**诊所提出争议**→复核后确认→**有未决争议财务确认被拒**→争议解决→**财务端最终确认**；月结详情含订单行、返工原因、争议记录；
   - 加急单 + 延误签收：加急费 90、延误扣罚 15、应付 375；取模气泡异常上报与客服处理；
   - 第三场景：**咬合高（misfitType=HIGH_BITE）收费返工**，缺 `chargeable` 确认被拒；48h 时限+收费重做；标记“医生取模问题”→诊所责任 100；结算 300+100=400。
2. **`./verify.sh` — 57 项断言全部通过**（针对 8080 端口运行中的服务，覆盖登录鉴权、OpenAPI/Swagger 可访问、下单、口扫文件、八道工序与设计版本、库存扣减、在线批注触发改设计异常、发货签收、试戴不密合返工证据链、诊所确认时限/收费、未确认拦截重做、返工原因三分类、重做版本、结算拦截与金额、月结同步/争议/复核/财务确认、按牙位看板、技工绩效、加急与配送延误场景）。
3. **Docker 交付物**：已提供 `Dockerfile`（多阶段构建）与 `docker-compose.yml`（postgres+app 一键启动）。当前执行环境未安装 docker 二进制，未在本机实际构建镜像；配置可在装有 Docker 的环境直接 `docker compose up -d --build` 运行。

## API 一览

统一响应：`{"success": true, "data": ...}`；错误：`{"success": false, "error": "..."}`。

| 方法 | 路径 | 角色 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 登录，返回 token |
| GET | `/api/auth/me` | 登录 | 当前账号 |
| POST | `/api/orders` | 诊所医生 | 提交订单（患者匿名编号、牙位、取模方式、咬合关系、色号、备注、预计试戴日期、材料要求、难度） |
| POST | `/api/orders/{id}/accept` | 前台/管理员 | 技工所接单 |
| POST | `/api/orders/{id}/rush` | 诊所/客服/管理员 | 患者加急（留痕异常事件，结算计加急费） |
| POST | `/api/orders/{id}/scan-files` | 诊所/技工所 | 登记数字口扫文件 |
| GET | `/api/orders` `/api/orders/{id}` | 登录 | 订单列表（诊所仅见本诊所）/ 详情聚合 |
| POST | `/api/orders/{id}/items/{itemId}/stages` | 技工/质检/前台 | 登记工序（操作人、材料批次与用量、照片、预计完成时间）；**完成扫描设计自动固化设计版本** |
| POST | `/api/orders/{id}/items/{itemId}/confirm` | 诊所医生 | 医生确认设计 |
| POST | `/api/orders/{id}/items/{itemId}/annotations` | 诊所医生 | 在线批注（设计冻结后批注自动登记“医生临时改设计”异常） |
| POST | `/api/annotations/{id}/resolve` | 技工所 | 标记批注已处理 |
| GET | `/api/orders/{id}/tooth-board` | 登录 | **按牙位看板**：设计、设计版本列表、制作、质检、配送、试戴、返工责任与原因 |
| POST | `/api/orders/{id}/exceptions` | 登录 | 上报异常（取模气泡/色号争议/改设计/排期拥堵/试戴不密合/加急/配送延误/返工责任不清） |
| POST | `/api/exceptions/{id}/handle` | 客服/管理员 | 处理异常 |
| GET | `/api/exceptions/open` | 登录 | 待处理异常工作台 |
| POST | `/api/orders/{id}/try-ins` | 诊所医生 | 记录试戴结果；MISFIT 必填 `misfitType=HIGH_BITE\|EDGE_MISFIT`，可附 `doctorNotes`、`photos` |
| GET | `/api/orders/{id}/try-ins` | 登录 | 试戴记录列表 |
| POST | `/api/orders/{id}/items/{itemId}/reworks` | 多角色 | 人工对单个牙位发起返工（生成待诊所确认单） |
| GET | `/api/reworks/awaiting-confirm` | 诊所 | **本诊所待确认返工列表** |
| POST | `/api/reworks/{id}/clinic-confirm` | 诊所 | **诊所确认返工**：`deadlineHours` 返工时限、`chargeable` 是否收费、`confirmNote` |
| POST | `/api/reworks/{id}/clinic-reject` | 诊所 | 诊所驳回返工（有异议，填 rejectReason） |
| POST | `/api/reworks/{id}/mark-cause` | 管理员/质检 | **技工所标记返工原因**：`DESIGN` 设计问题 / `PROCESSING` 加工问题 / `IMPRESSION` 医生取模问题 |
| POST | `/api/reworks/{id}/assign-responsibility` | 管理员/客服 | 判定返工责任与成本（须先标记原因；诊所/技工所/共担） |
| POST | `/api/reworks/{id}/clinic-review` | 诊所 | 复核返工（对账前置条件） |
| GET | `/api/reworks/unreviewed` | 诊所 | 本诊所待复核返工 |
| GET | `/api/reworks/{id}` | 登录 | 返工单详情（证据链、时限、收费、原因、责任、复核） |
| GET/POST | `/api/inventory/materials` `/api/inventory/materials/{id}/batches` | 技工所 | 材料档案、批次入库、库存总览（含低库存预警） |
| POST | `/api/orders/{id}/shipments` | 前台/配送 | 发货（可只发指定牙位，支持返工后重发） |
| POST | `/api/shipments/{id}/sign` | 诊所/配送 | 签收：完好/延误/破损，直接影响试戴安排 |
| POST | `/api/orders/{id}/settlement` | 财务/管理员 | 生成订单结算单（未标记返工原因/未判责任均拦截） |
| GET | `/api/orders/{id}/settlement` | 登录 | 查看结算单 |
| POST | `/api/statements/generate` | 财务/管理员 | 生成诊所月结单（汇总订单结算与返工责任调整） |
| POST | `/api/statements/{id}/lab-sync` | 技工所/财务 | **技工所同步月结单**（同步后诊所可见可确认） |
| POST | `/api/statements/{id}/confirm` | 诊所管理员/医生 | **诊所确认月结**（账期内返工须全部复核） |
| POST | `/api/statements/{id}/finance-confirm` | 财务/管理员 | **财务端最终确认**（有未解决月结争议时拦截） |
| GET | `/api/statements/{id}` | 登录 | 月结详情：订单结算行、返工责任调整、争议记录 |
| GET | `/api/statements` | 登录 | 月结单列表 |
| POST | `/api/statements/{id}/disputes` | 诊所/技工所 | **提出月结争议**（双向同步记录） |
| POST | `/api/statements/disputes/{disputeId}/resolve` | 诊所/技工所 | 处理月结争议 |
| GET | `/api/statements/{id}/disputes` | 登录 | 月结单的争议记录 |
| GET | `/api/statements/disputes/open` | 登录 | 未解决月结争议工作台 |
| GET | `/api/performance/technicians` | 管理员/财务 | 技工绩效（完成工序数、准时率、返工牙位数、材料用量） |

## 核心业务规则

- **按牙位流转**：每颗牙独立走八道工序（`MODEL_RECEIVING → SCAN_DESIGN → DOCTOR_CONFIRM → TEETH_ARRANGEMENT → SINTERING → POLISHING → QC → DELIVERY`），工序必须按序登记；每道工序记录操作人、材料批次与用量（实时扣减库存，不足报错）、照片、预计完成时间。返工只打回对应牙位，不影响整单其他牙位。
- **设计版本**：牙位每完成一次扫描设计固化一个 `DesignVersion`（含设计者、摘要、照片），版本号按牙位递增；返工重做产生新版本并关联返工单。**试戴返工单锁定患者试戴时那一版设计**，作为判断设计问题的依据。
- **医生确认与批注**：扫描设计后必须由本诊所医生确认才能继续生产；设计冻结（确认）后的批注自动登记“医生临时改设计”异常。
- **试戴不密合返工**：医生记录试戴结果，不密合必须指明 **咬合高（HIGH_BITE）或边缘不密合（EDGE_MISFIT）**，并可附医生备注与试戴照片。系统自动：① 登记“试戴不密合”异常；② 创建“待诊所确认”返工单，**关联试戴那版设计、医生备注、返工前各工序技工操作快照、试戴照片**；③ 牙位进入“待确认返工”，**未经诊所确认不能重做**。配送破损同样生成待诊所确认返工单。
- **诊所确认返工**：诊所确认返工**完成时限（deadlineHours，据此计算 deadlineAt，逾期视图标红 overdue）**与**本次是否收费（chargeable：免费返修/收费重做）**，可填确认备注；诊所有异议可驳回（需线下协商，责任不清可上报异常）。确认后牙位才打回模型接收重做。
- **技工所标记原因三分类**：返工确认后，技工所管理员/质检必须标记为 **设计问题（DESIGN）/ 加工问题（PROCESSING）/ 医生取模问题（IMPRESSION）**，之后才能判定责任（诊所/技工所/双方共担）并核定成本分摊；未标记原因不能判责任，责任未判定不能结算。牙位重做重新通过质检后返工单自动关闭。
- **结算公式**：`应付 = 基础费(单价×牙位数) × 难度系数(1+0.15×(难度-1)) + 加急费(基础费×难度系数×加急费率) + 诊所承担返工费 − 技工所承担返工费 − 配送扣罚(延误5%/破损10%)`，最低为 0。结算明细逐条列出“返工#牙位 原因→责任→双方金额”的责任调整。各类型单价与加急费率存于 `price_config`。
- **诊所月结（多方协同）**：按订单完成时间归属账期汇总已结算订单，同时汇总账期内返工责任调整（诊所承担加收 / 技工所承担扣减）。状态机：**财务生成 GENERATED → 技工所同步 LAB_SYNCED（同步诊所）→ 诊所确认 CLINIC_CONFIRMED（账期内返工须全部复核）→ 财务端最终确认 FINANCE_CONFIRMED**。账期内存在未结算订单不能生成月结；技工所未同步诊所不能确认；**存在未解决月结争议时财务不能确认**。
- **月结争议**：诊所与技工所双方均可对月结单提出争议（如对返工扣减凭证有疑问），争议与月结单绑定、双向可见；任一方可登记处理结果，争议全部解决后财务才能最终确认。
- **技工绩效**：按技工统计完成工序数、准时率（对比预计完成时间）、经手牙位的返工数与材料用量。

## 工程结构

```
src/main/java/com/dentallab/
├── config/    # 拦截器、异常处理、OpenAPI 配置、种子数据
├── model/     # 19 个实体 + 18 个枚举
├── repo/      # Spring Data JPA 仓库
├── service/   # 11 个业务服务（认证/订单/生产/异常/返工/库存/配送/试戴/结算/月结/绩效）
└── web/       # 13 个 REST 控制器 + 请求解析辅助
src/test/java/com/dentallab/DentalLabFlowTest.java   # 10 个端到端集成测试
verify.sh      # 57 项断言的全流程 curl 验证脚本
Dockerfile     # 多阶段构建（Maven 构建 + JRE 运行）
docker-compose.yml   # postgres 15 + app 一键启动
```
