#!/usr/bin/env bash
# 全流程验证脚本：下单→接单→八道工序→发货→签收→试戴→不密合返工
#   （诊所确认时限/是否收费→标记原因→判定责任→重做）→结算
#   →月结（技工所同步→争议→诊所确认→财务确认）
# 前提：服务已启动（默认 http://localhost:8080）
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0

say()  { echo -e "\033[1;36m==>\033[0m $*"; }
ok()   { PASS=$((PASS+1)); echo -e "  \033[1;32m✔\033[0m $*"; }
bad()  { FAIL=$((FAIL+1)); echo -e "  \033[1;31m✘ $*\033[0m"; }
check() { # check <描述> <实际值> <期望值>
  if [ "$2" == "$3" ]; then ok "$1 ($2)"; else bad "$1: 期望[$3] 实际[$2]"; fi;
}
money() { awk -v v="$1" 'BEGIN{printf "%.2f", v}'; }
checkMoney() { # checkMoney <描述> <实际值> <期望值>
  check "$1" "$(money "$2")" "$(money "$3")"
}

req() { # req <方法> <路径> <token> <json-body>
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  local args=(-s -X "$method" "$BASE$path" -H "Content-Type: application/json")
  [ -n "$token" ] && args+=(-H "X-Auth-Token: $token")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}"
}

login() { # login <用户名> <密码> -> token
  req POST /api/auth/login "" "{\"username\":\"$1\",\"password\":\"$2\"}" | jq -r '.data.token'
}

say "0. 健康检查与登录"
check "健康检查" "$(req GET /api/health | jq -r '.status')" "UP"
check "OpenAPI 文档可访问" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v3/api-docs")" "200"
check "Swagger UI 可访问" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/swagger-ui/index.html")" "200"
DOCTOR=$(login doctor1 doctor123)
RECEIVER=$(login receiver lab123)
TECH1=$(login tech1 tech123)
TECH2=$(login tech2 tech123)
QC=$(login qc1 qc123)
CADMIN=$(login clinicadmin1 admin123)
LADMIN=$(login labadmin lab123)
SERVICE=$(login service1 service123)
FINANCE=$(login finance1 finance123)
[ -n "$DOCTOR" ] && [ "$DOCTOR" != "null" ] && ok "9 个角色全部登录成功" || bad "登录失败"
check "错误密码被拒绝" "$(req POST /api/auth/login '' '{"username":"doctor1","password":"x"}' | jq -r '.success')" "false"
check "无令牌访问被拒绝" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/orders")" "401"

say "1. 诊所提交全瓷冠订单（牙位 11/12，数字口扫，难度3）"
ORDER=$(req POST /api/orders "$DOCTOR" '{
  "patientCode":"PAT-VERIFY-001","prosthesisType":"ALL_CERAMIC_CROWN","impressionMethod":"DIGITAL_SCAN",
  "toothPositions":["11","12"],"biteRelation":"正常覆合覆盖","shade":"VITA A2",
  "doctorNotes":"邻面接触稍紧","expectedTryInDate":"2026-10-10","materialRequirement":"氧化锆全瓷","difficulty":3}')
OID=$(echo "$ORDER" | jq -r '.data.id')
ONO=$(echo "$ORDER" | jq -r '.data.orderNo')
ITEM1=$(echo "$ORDER" | jq -r '.data.toothItems[0].id')
ITEM2=$(echo "$ORDER" | jq -r '.data.toothItems[1].id')
check "订单创建" "$(echo "$ORDER" | jq -r '.data.status')" "SUBMITTED"
echo "  订单号: $ONO (id=$OID, 牙位项=$ITEM1,$ITEM2)"

req POST "/api/orders/$OID/scan-files" "$DOCTOR" '{"fileName":"pat001.stl","format":"STL","sizeBytes":1048576,"storagePath":"oss://scans/pat001.stl"}' > /dev/null
check "口扫文件登记" "$(req GET "/api/orders/$OID" "$DOCTOR" | jq -r '.data.scanFiles | length')" "1"

say "2. 技工所接单并推进八道工序（扫描设计自动生成设计版本 v1）"
req POST /api/orders/$OID/accept "$RECEIVER" > /dev/null
check "接单后状态" "$(req GET "/api/orders/$OID" "$RECEIVER" | jq -r '.data.status')" "ACCEPTED"

ZIRC_BATCH=$(req GET /api/inventory/materials "$LADMIN" | jq -r '.data[] | select(.code=="ZIRC") | .batches[0].id')
STOCK_BEFORE=$(req GET /api/inventory/materials "$LADMIN" | jq -r '.data[] | select(.code=="ZIRC") | .remainingQty')

stage() { # stage <item> <token> <stage> <extra-json>
  req POST "/api/orders/$OID/items/$1/stages" "$2" "{\"stage\":\"$3\",\"estimatedFinishAt\":\"2026-12-31T23:59:00\",\"finish\":true$4}" > /dev/null
}
for ITEM in $ITEM1 $ITEM2; do
  stage "$ITEM" "$RECEIVER" MODEL_RECEIVING ',"note":"模型接收"'
  stage "$ITEM" "$TECH1" SCAN_DESIGN ',"designSummary":"全瓷冠设计","photos":["design.jpg"]'
  req POST "/api/orders/$OID/items/$ITEM/confirm" "$DOCTOR" '{"note":"设计确认"}' > /dev/null
  stage "$ITEM" "$TECH1" TEETH_ARRANGEMENT ''
  stage "$ITEM" "$TECH2" SINTERING ",\"materialBatchId\":$ZIRC_BATCH,\"materialQty\":\"1\",\"photos\":[\"sinter.jpg\"]"
  stage "$ITEM" "$TECH1" POLISHING ''
  stage "$ITEM" "$QC" QC ',"note":"质检合格","photos":["qc.jpg"]'
  stage "$ITEM" "$RECEIVER" DELIVERY ',"note":"已打包"'
done
check "全部工序完成订单状态" "$(req GET "/api/orders/$OID" "$RECEIVER" | jq -r '.data.status')" "QC_PASSED"
check "牙位12设计版本v1" "$(req GET "/api/orders/$OID/tooth-board" "$LADMIN" | jq -r '[.data.teeth[] | select(.toothPosition=="12") | .designVersions | length][0]')" "1"
STOCK_AFTER=$(req GET /api/inventory/materials "$LADMIN" | jq -r '.data[] | select(.code=="ZIRC") | .remainingQty')
check "材料库存扣减(200-2)" "$(money "$STOCK_AFTER")" "$(money "$(awk -v a="$STOCK_BEFORE" 'BEGIN{print a-2}')")"

say "3. 医生在线批注（设计已冻结 → 自动登记改设计异常）"
req POST "/api/orders/$OID/items/$ITEM1/annotations" "$DOCTOR" '{"content":"切端透明度再低一点"}' > /dev/null
check "批注后自动产生改设计异常" "$(req GET "/api/orders/$OID/exceptions" "$DOCTOR" | jq -r '[.data[] | select(.type=="DESIGN_CHANGE")] | length')" "1"

say "4. 发货与完好签收 → 自动生成试戴安排"
SHIP=$(req POST "/api/orders/$OID/shipments" "$RECEIVER" '')
SHIP_ID=$(echo "$SHIP" | jq -r '.data.id')
check "发货单号生成" "$(echo "$SHIP" | jq -r '.data.trackingNo' | cut -c1-2)" "SF"
req POST "/api/shipments/$SHIP_ID/sign" "$CADMIN" '{"signResult":"GOOD","signedByName":"陈前台"}' > /dev/null
check "签收后订单状态" "$(req GET "/api/orders/$OID" "$DOCTOR" | jq -r '.data.status')" "TRY_IN"
check "试戴安排生成" "$(req GET "/api/orders/$OID/try-ins" "$DOCTOR" | jq -r '.data | length')" "2"

say "5. 牙位11密合，牙位12边缘不密合 → 待诊所确认返工（关联设计版本/医生备注/技工操作/照片）"
req POST "/api/orders/$OID/try-ins" "$DOCTOR" "{\"toothItemId\":$ITEM1,\"result\":\"FIT\",\"feedback\":\"密合良好\",\"experienceScore\":5}" > /dev/null
req POST "/api/orders/$OID/try-ins" "$DOCTOR" "{\"toothItemId\":$ITEM2,\"result\":\"MISFIT\",\"misfitType\":\"EDGE_MISFIT\",\"doctorNotes\":\"边缘缝隙食物嵌塞\",\"photos\":[\"tryin_12.jpg\"],\"experienceScore\":2}" > /dev/null
check "不密合自动登记异常" "$(req GET "/api/orders/$OID/exceptions" "$DOCTOR" | jq -r '[.data[] | select(.type=="TRY_IN_MISFIT")] | length')" "1"
REWORK_ID=$(req GET "/api/orders/$OID/reworks" "$DOCTOR" | jq -r '.data[0].id')
check "返工单初始状态" "$(req GET "/api/reworks/$REWORK_ID" "$DOCTOR" | jq -r '.data.status')" "AWAIT_CONFIRM"
check "返工问题分类" "$(req GET "/api/reworks/$REWORK_ID" "$DOCTOR" | jq -r '.data.misfitType')" "EDGE_MISFIT"
check "返工关联设计版本" "$(req GET "/api/reworks/$REWORK_ID" "$DOCTOR" | jq -r '.data.designVersionId != null')" "true"
check "返工留痕技工操作" "$(req GET "/api/reworks/$REWORK_ID" "$DOCTOR" | jq -r '.data.technicianOps != null and (.data.technicianOps | length > 0)')" "true"
check "待诊所确认列表" "$(req GET /api/reworks/awaiting-confirm "$CADMIN" | jq -r '[.data[] | select(.id=='$REWORK_ID')] | length')" "1"

say "6. 未确认返工时：订单不能结算、牙位不能重做"
check "结算拦截(订单未完成)" "$(req POST "/api/orders/$OID/settlement" "$FINANCE" '' | jq -r '.success')" "false"
check "重做拦截(待诊所确认)" "$(req POST "/api/orders/$OID/items/$ITEM2/stages" "$RECEIVER" '{"stage":"MODEL_RECEIVING","finish":true}' | jq -r '.error' | grep -c '诊所确认')" "1"

say "7. 诊所确认返工（72小时时限/免费返修）→ 重做 → 重发 → 试戴密合"
check "缺是否收费确认被拒" "$(req POST "/api/reworks/$REWORK_ID/clinic-confirm" "$CADMIN" '{"deadlineHours":72}' | jq -r '.success')" "false"
req POST "/api/reworks/$REWORK_ID/clinic-confirm" "$CADMIN" '{"deadlineHours":72,"chargeable":false,"confirmNote":"边缘重做"}' > /dev/null
check "返工单已确认" "$(req GET "/api/reworks/$REWORK_ID" "$DOCTOR" | jq -r '.data.status')" "CONFIRMED"
check "返工不收费" "$(req GET "/api/reworks/$REWORK_ID" "$DOCTOR" | jq -r '.data.chargeable')" "false"
req POST "/api/orders/$OID/exceptions" "$SERVICE" "{\"type\":\"REWORK_DISPUTE\",\"description\":\"责任分歧\",\"toothItemId\":$ITEM2}" > /dev/null
for S in MODEL_RECEIVING SCAN_DESIGN; do stage "$ITEM2" "$TECH1" "$S" ''; done
req POST "/api/orders/$OID/items/$ITEM2/confirm" "$DOCTOR" > /dev/null
stage "$ITEM2" "$TECH1" TEETH_ARRANGEMENT ''
stage "$ITEM2" "$TECH2" SINTERING ",\"materialBatchId\":$ZIRC_BATCH,\"materialQty\":\"1\""
stage "$ITEM2" "$TECH1" POLISHING ''
stage "$ITEM2" "$QC" QC ''
stage "$ITEM2" "$RECEIVER" DELIVERY ''
check "返工单自动完成" "$(req GET "/api/orders/$OID/reworks" "$DOCTOR" | jq -r '.data[0].status')" "COMPLETED"
check "重做产生设计版本v2" "$(req GET "/api/orders/$OID/tooth-board" "$LADMIN" | jq -r '[.data.teeth[] | select(.toothPosition=="12") | .designVersions | length][0]')" "2"
SHIP2=$(req POST "/api/orders/$OID/shipments" "$RECEIVER" "{\"itemIds\":[$ITEM2]}")
SHIP2_ID=$(echo "$SHIP2" | jq -r '.data.id')
req POST "/api/shipments/$SHIP2_ID/sign" "$CADMIN" '{"signResult":"GOOD","signedByName":"陈前台"}' > /dev/null
req POST "/api/orders/$OID/try-ins" "$DOCTOR" "{\"toothItemId\":$ITEM2,\"result\":\"FIT\",\"experienceScore\":4}" > /dev/null
check "订单完成" "$(req GET "/api/orders/$OID" "$DOCTOR" | jq -r '.data.status')" "COMPLETED"

say "8. 未标记原因/责任 → 结算拦截；标记加工问题+技工所责任200 → 结算"
check "结算拦截(返工责任不清)" "$(req POST "/api/orders/$OID/settlement" "$FINANCE" '' | jq -r '.error' | grep -c '返工责任')" "1"
check "未标记原因不能判责任" "$(req POST "/api/reworks/$REWORK_ID/assign-responsibility" "$LADMIN" '{"responsibility":"LAB","reworkCost":200}' | jq -r '.success')" "false"
req POST "/api/reworks/$REWORK_ID/mark-cause" "$LADMIN" '{"cause":"PROCESSING","causeNote":"烧结收缩控制不当"}' > /dev/null
check "返工原因三分类" "$(req GET "/api/reworks/$REWORK_ID" "$DOCTOR" | jq -r '.data.cause')" "PROCESSING"
req POST "/api/reworks/$REWORK_ID/assign-responsibility" "$LADMIN" '{"responsibility":"LAB","reworkCost":200}' > /dev/null
SETTLE=$(req POST "/api/orders/$OID/settlement" "$FINANCE" '')
checkMoney "基础费(500×2)" "$(echo "$SETTLE" | jq -r '.data.baseAmount')" "1000"
checkMoney "难度系数" "$(echo "$SETTLE" | jq -r '.data.difficultyMultiplier')" "1.30"
checkMoney "技工所承担返工扣减" "$(echo "$SETTLE" | jq -r '.data.reworkLabDeduction')" "200"
checkMoney "应付总额(1300-200)" "$(echo "$SETTLE" | jq -r '.data.totalAmount')" "1100"
check "结算明细含责任调整" "$(echo "$SETTLE" | jq -r '.data.detail | contains("返工责任调整")')" "true"

say "9. 月结：生成→技工所同步→争议→复核→诊所确认→财务确认"
YM=$(date +%Y-%m)
STMT=$(req POST /api/statements/generate "$FINANCE" "{\"clinicId\":1,\"yearMonth\":\"$YM\"}")
STMT_ID=$(echo "$STMT" | jq -r '.data.id')
check "月结单初始状态" "$(echo "$STMT" | jq -r '.data.status')" "GENERATED"
check "月结返工技工所扣减合计" "$(echo "$STMT" | jq -r '.data.reworkLabDeduction')" "200"
check "技工所未同步不能确认" "$(req POST "/api/statements/$STMT_ID/confirm" "$CADMIN" '' | jq -r '.error' | grep -c '同步')" "1"
check "同步前诊所列表不可见" "$(req GET /api/statements "$CADMIN" | jq -r '[.data[] | select(.id=='$STMT_ID')] | length')" "0"
req POST "/api/statements/$STMT_ID/lab-sync" "$LADMIN" > /dev/null
check "技工所同步后状态" "$(req GET "/api/statements/$STMT_ID" "$LADMIN" | jq -r '.data.status')" "LAB_SYNCED"
check "未复核时诊所确认被拒" "$(req POST "/api/statements/$STMT_ID/confirm" "$CADMIN" '' | jq -r '.error' | grep -c '复核')" "1"
DISPUTE_ID=$(req POST "/api/statements/$STMT_ID/disputes" "$CADMIN" '{"description":"返工扣减200需核对凭证"}' | jq -r '.data.id')
check "争议提出方" "$(req GET "/api/statements/$STMT_ID/disputes" "$CADMIN" | jq -r '.data[0].raisedSide')" "CLINIC"
req POST "/api/reworks/$REWORK_ID/clinic-review" "$CADMIN" > /dev/null
req POST "/api/statements/$STMT_ID/confirm" "$CADMIN" > /dev/null
check "诊所确认后状态" "$(req GET "/api/statements/$STMT_ID" "$LADMIN" | jq -r '.data.status')" "CLINIC_CONFIRMED"
check "有未决争议财务确认被拒" "$(req POST "/api/statements/$STMT_ID/finance-confirm" "$FINANCE" '' | jq -r '.error' | grep -c '争议')" "1"
req POST "/api/statements/disputes/$DISPUTE_ID/resolve" "$LADMIN" '{"resolution":"已提供质检凭证，双方认可"}' > /dev/null
req POST "/api/statements/$STMT_ID/finance-confirm" "$FINANCE" > /dev/null
check "财务端最终确认" "$(req GET "/api/statements/$STMT_ID" "$FINANCE" | jq -r '.data.status')" "FINANCE_CONFIRMED"
check "月结详情返工原因" "$(req GET "/api/statements/$STMT_ID" "$FINANCE" | jq -r '.data.reworks[0].cause')" "PROCESSING"

say "10. 按牙位看板 / 技工绩效 / 加急+延误场景"
check "牙位12看板含返工责任" "$(req GET "/api/orders/$OID/tooth-board" "$LADMIN" | jq -r '[.data.teeth[] | select(.toothPosition=="12") | .reworks[0].responsibility][0]')" "LAB"
check "技工绩效有工序记录" "$(req GET "/api/performance/technicians" "$LADMIN" | jq -r '[.data[] | select(.technicianName=="张技工") | .stagesCompleted][0] > 0')" "true"

ORDER2=$(req POST /api/orders "$DOCTOR" '{"patientCode":"PAT-VERIFY-002","prosthesisType":"PFM_CROWN","impressionMethod":"TRADITIONAL","toothPositions":["36"],"shade":"VITA B1","expectedTryInDate":"2026-10-05","difficulty":1}')
OID2=$(echo "$ORDER2" | jq -r '.data.id')
ITEM3=$(echo "$ORDER2" | jq -r '.data.toothItems[0].id')
req POST "/api/orders/$OID2/rush" "$DOCTOR" '{"reason":"患者出差加急"}' > /dev/null
EXC=$(req POST "/api/orders/$OID2/exceptions" "$DOCTOR" "{\"type\":\"IMPRESSION_BUBBLE\",\"description\":\"36远中气泡\",\"toothItemId\":$ITEM3}")
EXC_ID=$(echo "$EXC" | jq -r '.data.id')
check "取模气泡异常上报" "$(echo "$EXC" | jq -r '.data.status')" "OPEN"
check "客服处理异常" "$(req POST "/api/exceptions/$EXC_ID/handle" "$SERVICE" '{"resolution":"重新取模","resolved":true}' | jq -r '.data.status')" "RESOLVED"
req POST "/api/orders/$OID2/accept" "$RECEIVER" > /dev/null
stage2() { req POST "/api/orders/$OID2/items/$ITEM3/stages" "$2" "{\"stage\":\"$1\",\"finish\":true}" > /dev/null; }
stage2 MODEL_RECEIVING "$RECEIVER"; stage2 SCAN_DESIGN "$TECH1"
req POST "/api/orders/$OID2/items/$ITEM3/confirm" "$DOCTOR" > /dev/null
stage2 TEETH_ARRANGEMENT "$TECH1"; stage2 SINTERING "$TECH2"; stage2 POLISHING "$TECH1"; stage2 QC "$QC"; stage2 DELIVERY "$RECEIVER"
SHIP3=$(req POST "/api/orders/$OID2/shipments" "$RECEIVER" '')
SHIP3_ID=$(echo "$SHIP3" | jq -r '.data.id')
req POST "/api/shipments/$SHIP3_ID/sign" "$CADMIN" '{"signResult":"LATE","signedByName":"陈前台","delayReason":"暴雨"}' > /dev/null
check "延误签收自动登记异常" "$(req GET "/api/orders/$OID2/exceptions" "$DOCTOR" | jq -r '[.data[] | select(.type=="DELIVERY_DELAY")] | length')" "1"
req POST "/api/orders/$OID2/try-ins" "$DOCTOR" "{\"toothItemId\":$ITEM3,\"result\":\"FIT\",\"experienceScore\":4}" > /dev/null
SETTLE2=$(req POST "/api/orders/$OID2/settlement" "$FINANCE" '')
checkMoney "加急费(300×0.3)" "$(echo "$SETTLE2" | jq -r '.data.rushFee')" "90"
checkMoney "延误扣罚(300×0.05)" "$(echo "$SETTLE2" | jq -r '.data.deliveryPenalty')" "15"
checkMoney "加急单应付(300+90-15)" "$(echo "$SETTLE2" | jq -r '.data.totalAmount')" "375"

echo ""
echo "==============================================="
echo -e "验证结果: \033[1;32m通过 $PASS\033[0m 项, \033[1;31m失败 $FAIL\033[0m 项"
[ "$FAIL" -eq 0 ] && echo "ALL CHECKS PASSED" || exit 1
