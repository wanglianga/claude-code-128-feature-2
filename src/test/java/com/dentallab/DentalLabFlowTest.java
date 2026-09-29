package com.dentallab;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端流程测试（真实 PostgreSQL，dentallab_test 库）：
 * 下单→接单→八道工序→发货→签收→试戴→返工→责任判定→重做→再签收→结算→月结复核。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DentalLabFlowTest {

    @org.junit.jupiter.api.BeforeEach
    void configureRestTemplate() {
        // JDK HttpClient：避免 HttpURLConnection 对 401 的流式重试问题
        plain = new RestTemplate(new JdkClientHttpRequestFactory());
        plain.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.HttpStatusCode statusCode) {
                return false;
            }
        });
    }

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    private RestTemplate plain;

    private static String doctorToken;
    private static String receiverToken;
    private static String tech1Token;
    private static String tech2Token;
    private static String qcToken;
    private static String clinicAdminToken;
    private static String labAdminToken;
    private static String serviceToken;
    private static String financeToken;

    private static Long orderId;
    private static Long item1;
    private static Long item2;
    private static Long reworkId;
    private static Long zirconiaBatchId;
    private static Long statementId;

    // ---------- 主流程 ----------

    @Test
    @Order(1)
    void loginAllRoles() {
        doctorToken = login("doctor1", "doctor123");
        receiverToken = login("receiver", "lab123");
        tech1Token = login("tech1", "tech123");
        tech2Token = login("tech2", "tech123");
        qcToken = login("qc1", "qc123");
        clinicAdminToken = login("clinicadmin1", "admin123");
        labAdminToken = login("labadmin", "lab123");
        serviceToken = login("service1", "service123");
        financeToken = login("finance1", "finance123");

        // 错误密码应 401
        ResponseEntity<Map> bad = plain.postForEntity(url("/api/auth/login"),
                Map.of("username", "doctor1", "password", "wrong"), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, bad.getStatusCode());
        // 无令牌访问受保护接口应 401
        ResponseEntity<Map> noAuth = plain.getForEntity(url("/api/orders"), Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noAuth.getStatusCode());
    }

    @Test
    @Order(2)
    void clinicCreatesOrder() {
        Map<String, Object> body = new HashMap<>();
        body.put("patientCode", "PAT-2026-0001");
        body.put("prosthesisType", "ALL_CERAMIC_CROWN");
        body.put("impressionMethod", "DIGITAL_SCAN");
        body.put("toothPositions", List.of("11", "12"));
        body.put("biteRelation", "正常覆合覆盖");
        body.put("shade", "VITA A2");
        body.put("doctorNotes", "邻面接触稍紧");
        body.put("expectedTryInDate", LocalDate.now().plusDays(7).toString());
        body.put("materialRequirement", "氧化锆全瓷");
        body.put("difficulty", 3);
        Map<String, Object> resp = data(post(doctorToken, "/api/orders", body));
        orderId = toLong(resp.get("id"));
        assertNotNull(resp.get("orderNo"));
        assertEquals("SUBMITTED", resp.get("status"));
        List<Map<String, Object>> items = list(resp, "toothItems");
        assertEquals(2, items.size());
        item1 = toLong(items.get(0).get("id"));
        item2 = toLong(items.get(1).get("id"));

        // 数字口扫文件登记
        Map<String, Object> scan = data(post(doctorToken, "/api/orders/" + orderId + "/scan-files",
                Map.of("fileName", "pat0001_upper.stl", "format", "STL",
                        "sizeBytes", 2048576, "storagePath", "oss://scans/pat0001_upper.stl")));
        assertEquals("STL", scan.get("format"));

        // 诊所医生不能给别的角色下单权限之外的操作：技工角色不能下单
        ResponseEntity<Map> forbidden = postRaw(tech1Token, "/api/orders", body);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
    }

    @Test
    @Order(3)
    void labAcceptsAndProduces() {
        // 未接单前不能登记工序
        ResponseEntity<Map> early = postRaw(tech1Token, "/api/orders/" + orderId + "/items/" + item1 + "/stages",
                Map.of("stage", "MODEL_RECEIVING"));
        assertEquals(HttpStatus.BAD_REQUEST, early.getStatusCode());

        data(post(receiverToken, "/api/orders/" + orderId + "/accept", null));

        for (Long item : List.of(item1, item2)) {
            // 模型接收（前台）
            data(post(receiverToken, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("MODEL_RECEIVING", "收到口扫数据与模型", null, null, null)));
            // 扫描设计（技工1），写设计摘要
            Map<String, Object> design = stageBody("SCAN_DESIGN", "设计完成", null, null, null);
            design.put("designSummary", "全瓷冠设计，边缘位于龈上0.5mm");
            data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item + "/stages", design));
        }

        // 医生对 11 牙位在线批注（设计未冻结，不触发异常）
        data(post(doctorToken, "/api/orders/" + orderId + "/items/" + item1 + "/annotations",
                Map.of("content", "切端透明度再低一点")));

        for (Long item : List.of(item1, item2)) {
            // 医生确认
            data(post(doctorToken, "/api/orders/" + orderId + "/items/" + item + "/confirm",
                    Map.of("note", "设计确认，可以制作")));
            // 排牙（技工1）
            data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("TEETH_ARRANGEMENT", "排牙完成", null, null, null)));
        }

        // 材料批次：查氧化锆瓷块批次
        Map<String, Object> materials = data(get(labAdminToken, "/api/inventory/materials"));
        List<Map<String, Object>> mats = list(materials, "$data");
        Map<String, Object> zirconia = mats.stream()
                .filter(m -> "ZIRC".equals(m.get("code"))).findFirst().orElseThrow();
        List<Map<String, Object>> batches = list(zirconia, "batches");
        zirconiaBatchId = toLong(batches.get(0).get("id"));
        BigDecimal before = new BigDecimal(String.valueOf(batches.get(0).get("remainingQty")));

        for (Long item : List.of(item1, item2)) {
            // 烧结（技工2，使用氧化锆瓷块 1 块）
            data(post(tech2Token, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("SINTERING", "烧结完成", zirconiaBatchId, "1", List.of("photo_sinter_" + item + ".jpg"))));
            // 抛光（技工1）
            data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("POLISHING", "抛光完成", null, null, null)));
            // 质检（质检员）
            data(post(qcToken, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("QC", "质检合格", null, null, List.of("photo_qc_" + item + ".jpg"))));
            // 配送工序登记（前台）
            data(post(receiverToken, "/api/orders/" + orderId + "/items/" + item + "/stages",
                    stageBody("DELIVERY", "已打包待发货", null, null, null)));
        }

        // 库存被扣减 2 块
        Map<String, Object> after = data(get(labAdminToken, "/api/inventory/materials"));
        Map<String, Object> zircAfter = list(after, "$data").stream()
                .filter(m -> "ZIRC".equals(m.get("code"))).findFirst().orElseThrow();
        assertEquals(0, before.subtract(new BigDecimal("2"))
                .compareTo(new BigDecimal(String.valueOf(zircAfter.get("remainingQty")))));

        // 工序顺序错误应失败：已完成全部工序的牙位不能再登记
        ResponseEntity<Map> wrongSeq = postRaw(tech1Token, "/api/orders/" + orderId + "/items/" + item1 + "/stages",
                stageBody("POLISHING", "重复抛光", null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, wrongSeq.getStatusCode());

        Map<String, Object> detail = data(get(doctorToken, "/api/orders/" + orderId));
        assertEquals("QC_PASSED", detail.get("status"));
    }

    @Test
    @Order(4)
    void shipAndSignThenTryIn() {
        // 发货
        Map<String, Object> shipment = data(post(receiverToken, "/api/orders/" + orderId + "/shipments", null));
        Long shipmentId = toLong(shipment.get("id"));
        assertNotNull(shipment.get("trackingNo"));

        // 诊所完好签收 → 自动生成试戴安排
        data(post(clinicAdminToken, "/api/shipments/" + shipmentId + "/sign",
                Map.of("signResult", "GOOD", "signedByName", "陈前台")));
        Map<String, Object> detail = data(get(doctorToken, "/api/orders/" + orderId));
        assertEquals("TRY_IN", detail.get("status"));
        assertFalse(list(detail, "tryIns").isEmpty());

        // 牙位 11 试戴密合
        Map<String, Object> fit = new HashMap<>();
        fit.put("toothItemId", item1);
        fit.put("result", "FIT");
        fit.put("feedback", "密合良好，患者满意");
        fit.put("experienceScore", 5);
        data(post(doctorToken, "/api/orders/" + orderId + "/try-ins", fit));

        // 牙位 12 试戴不密合（边缘不密合）→ 自动异常 + 待诊所确认返工单，附带证据链
        Map<String, Object> misfit = new HashMap<>();
        misfit.put("toothItemId", item2);
        misfit.put("result", "MISFIT");
        misfit.put("misfitType", "EDGE_MISFIT");
        misfit.put("doctorNotes", "边缘不密合，食物嵌塞");
        misfit.put("photos", List.of("tryin_12_edge.jpg"));
        misfit.put("feedback", "边缘不密合，食物嵌塞");
        misfit.put("experienceScore", 2);
        data(post(doctorToken, "/api/orders/" + orderId + "/try-ins", misfit));

        Map<String, Object> after = data(get(doctorToken, "/api/orders/" + orderId));
        List<Map<String, Object>> exceptions = list(after, "exceptions");
        assertTrue(exceptions.stream().anyMatch(e -> "TRY_IN_MISFIT".equals(e.get("type"))));
        List<Map<String, Object>> reworks = list(after, "reworks");
        assertEquals(1, reworks.size());
        Map<String, Object> rw0 = reworks.get(0);
        assertEquals("AWAIT_CONFIRM", rw0.get("status"));
        assertEquals("PENDING", rw0.get("responsibility"));
        assertEquals("EDGE_MISFIT", rw0.get("misfitType"));
        assertEquals("TRY_IN_MISFIT", rw0.get("source"));
        assertNotNull(rw0.get("designVersionId"), "返工单应关联试戴那版设计");
        assertNotNull(rw0.get("technicianOps"), "返工单应留痕技工操作");
        assertNull(rw0.get("chargeable"));
        reworkId = toLong(rw0.get("id"));
    }

    @Test
    @Order(5)
    void reworkResponsibilityAndRedo() {
        // 客服上报“返工责任不清”异常
        data(post(serviceToken, "/api/orders/" + orderId + "/exceptions",
                Map.of("type", "REWORK_DISPUTE", "description", "诊所与技工所对返工责任有分歧",
                        "toothItemId", item2)));

        // 返工未经诊所确认前，牙位不能重做
        ResponseEntity<Map> redoBlocked = postRaw(receiverToken,
                "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("MODEL_RECEIVING", "未确认就返工", null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, redoBlocked.getStatusCode());
        assertTrue(String.valueOf(Objects.requireNonNull(redoBlocked.getBody()).get("error"))
                .contains("诊所确认"));

        // 诊所确认返工：时限 72 小时、本次免费返修（不收费）
        Map<String, Object> confirmed = data(post(clinicAdminToken,
                "/api/reworks/" + reworkId + "/clinic-confirm",
                Map.of("deadlineHours", 72, "chargeable", false,
                        "confirmNote", "咬合可接受，边缘需重做")));
        assertEquals("CONFIRMED", confirmed.get("status"));
        assertEquals(Boolean.FALSE, confirmed.get("chargeable"));
        assertEquals(72, ((Number) confirmed.get("deadlineHours")).intValue());
        assertNotNull(confirmed.get("deadlineAt"));

        // 未标记返工原因前不能判定责任
        ResponseEntity<Map> assignBlocked = postRaw(labAdminToken,
                "/api/reworks/" + reworkId + "/assign-responsibility",
                Map.of("responsibility", "LAB", "reworkCost", 200));
        assertEquals(HttpStatus.BAD_REQUEST, assignBlocked.getStatusCode());

        // 牙位 12 重做：从模型接收重新开始（扫描设计会产生设计版本 v2 并关联返工单）
        data(post(receiverToken, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("MODEL_RECEIVING", "返工模型接收", null, null, null)));
        data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("SCAN_DESIGN", "返工设计", null, null, null)));
        data(post(doctorToken, "/api/orders/" + orderId + "/items/" + item2 + "/confirm", null));
        data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("TEETH_ARRANGEMENT", "返工排牙", null, null, null)));
        data(post(tech2Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("SINTERING", "返工烧结", zirconiaBatchId, "1", null)));
        data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("POLISHING", "返工抛光", null, null, null)));
        data(post(qcToken, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("QC", "返工质检合格", null, null, null)));
        data(post(receiverToken, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("DELIVERY", "返工打包", null, null, null)));

        // 返工单应已自动完成；其关联的是患者试戴那版设计 v1
        Map<String, Object> reworks = data(get(doctorToken, "/api/orders/" + orderId + "/reworks"));
        Map<String, Object> rework = list(reworks, "$data").get(0);
        assertEquals("COMPLETED", rework.get("status"));
        assertEquals(1, ((Number) rework.get("designVersionNo")).intValue(),
                "返工证据应锁定患者试戴时的第一版设计");

        // 只重发牙位 12
        Map<String, Object> shipment = data(post(receiverToken, "/api/orders/" + orderId + "/shipments",
                Map.of("itemIds", List.of(item2))));
        Long shipmentId = toLong(shipment.get("id"));
        data(post(clinicAdminToken, "/api/shipments/" + shipmentId + "/sign",
                Map.of("signResult", "GOOD", "signedByName", "陈前台")));

        // 牙位 12 再次试戴密合 → 订单完成
        Map<String, Object> fit = new HashMap<>();
        fit.put("toothItemId", item2);
        fit.put("result", "FIT");
        fit.put("feedback", "返工后密合");
        fit.put("experienceScore", 4);
        data(post(doctorToken, "/api/orders/" + orderId + "/try-ins", fit));

        Map<String, Object> detail = data(get(doctorToken, "/api/orders/" + orderId));
        assertEquals("COMPLETED", detail.get("status"));
        assertNotNull(detail.get("completedAt"));

        // 返工责任未判定前不能结算
        ResponseEntity<Map> blocked = postRaw(financeToken, "/api/orders/" + orderId + "/settlement", null);
        assertEquals(HttpStatus.BAD_REQUEST, blocked.getStatusCode());
        assertTrue(String.valueOf(Objects.requireNonNull(blocked.getBody()).get("error")).contains("返工责任"));

        // 技工所先标记原因：加工问题；管理员再判定责任：技工所承担 200
        data(post(labAdminToken, "/api/reworks/" + reworkId + "/mark-cause",
                Map.of("cause", "PROCESSING", "causeNote", "烧结收缩控制不当导致边缘缝隙")));
        data(post(labAdminToken, "/api/reworks/" + reworkId + "/assign-responsibility",
                Map.of("responsibility", "LAB", "reworkCost", 200)));
    }

    @Test
    @Order(6)
    void settlementReflectsReworkResponsibility() {
        Map<String, Object> s = data(post(financeToken, "/api/orders/" + orderId + "/settlement", null));
        // 基础费 500×2=1000，难度3系数1.30 → 1300；技工所承担返工 200 扣减 → 1100
        assertMoney("1000.00", s.get("baseAmount"));
        assertMoney("1.30", s.get("difficultyMultiplier"));
        assertMoney("0.00", s.get("rushFee"));
        assertMoney("0.00", s.get("reworkClinicShare"));
        assertMoney("200.00", s.get("reworkLabDeduction"));
        assertMoney("0.00", s.get("deliveryPenalty"));
        assertMoney("1100.00", s.get("totalAmount"));
    }

    @Test
    @Order(7)
    void monthlyStatementSyncDisputeAndFinanceConfirm() {
        String ym = java.time.YearMonth.now().toString();
        // 财务生成月结单（含返工责任调整：技工所承担 200）
        Map<String, Object> statement = data(post(financeToken, "/api/statements/generate",
                Map.of("clinicId", 1, "yearMonth", ym)));
        statementId = toLong(statement.get("id"));
        assertEquals("GENERATED", statement.get("status"));
        assertEquals(1, ((Number) statement.get("unreviewedReworkCount")).intValue());
        assertMoney("1100.00", statement.get("totalAmount"));
        assertMoney("200.00", statement.get("reworkLabDeduction"));

        // 技工所未同步前，诊所确认被拒，且月结单对诊所不可见
        ResponseEntity<Map> beforeSync = postRaw(clinicAdminToken,
                "/api/statements/" + statementId + "/confirm", null);
        assertEquals(HttpStatus.BAD_REQUEST, beforeSync.getStatusCode());
        assertTrue(String.valueOf(Objects.requireNonNull(beforeSync.getBody()).get("error")).contains("同步"));
        org.springframework.http.HttpHeaders hh = new org.springframework.http.HttpHeaders();
        hh.set("X-Auth-Token", clinicAdminToken);
        ResponseEntity<Map> hiddenResp = plain.exchange(
                "http://localhost:" + port + "/api/statements/" + statementId,
                org.springframework.http.HttpMethod.GET, new org.springframework.http.HttpEntity<>(hh), Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, hiddenResp.getStatusCode());
        ResponseEntity<Map> clinicList = get(clinicAdminToken, "/api/statements");
        assertEquals(0, list(data(clinicList), "$data").size());

        // 技工所同步月结单（同步后诊所可见）
        Map<String, Object> synced = data(post(labAdminToken,
                "/api/statements/" + statementId + "/lab-sync", null));
        assertEquals("LAB_SYNCED", synced.get("status"));
        assertNotNull(synced.get("labSyncedAt"));

        // 已同步但返工未复核 → 诊所确认被拒
        ResponseEntity<Map> confirmBlocked = postRaw(clinicAdminToken,
                "/api/statements/" + statementId + "/confirm", null);
        assertEquals(HttpStatus.BAD_REQUEST, confirmBlocked.getStatusCode());
        assertTrue(String.valueOf(Objects.requireNonNull(confirmBlocked.getBody()).get("error")).contains("复核"));

        // 诊所先提出一条月结争议（对返工扣减 200 有疑问）
        Map<String, Object> dispute = data(post(clinicAdminToken,
                "/api/statements/" + statementId + "/disputes",
                Map.of("description", "返工扣减 200 需要核对加工责任凭证")));
        Long disputeId = toLong(dispute.get("id"));
        assertEquals("CLINIC", dispute.get("raisedSide"));
        assertEquals("OPEN", dispute.get("status"));

        // 诊所复核全部返工
        Map<String, Object> unreviewed = data(get(clinicAdminToken, "/api/reworks/unreviewed"));
        assertEquals(1, list(unreviewed, "$data").size());
        data(post(clinicAdminToken, "/api/reworks/" + reworkId + "/clinic-review", null));

        // 复核后诊所确认成功
        Map<String, Object> confirmed = data(post(clinicAdminToken,
                "/api/statements/" + statementId + "/confirm", null));
        assertEquals("CLINIC_CONFIRMED", confirmed.get("status"));

        // 存在未解决争议 → 财务确认被拦截
        ResponseEntity<Map> financeBlocked = postRaw(financeToken,
                "/api/statements/" + statementId + "/finance-confirm", null);
        assertEquals(HttpStatus.BAD_REQUEST, financeBlocked.getStatusCode());
        assertTrue(String.valueOf(Objects.requireNonNull(financeBlocked.getBody()).get("error")).contains("争议"));

        // 技工所处理争议（凭证已同步给诊所，双方协商一致）
        Map<String, Object> resolved = data(post(labAdminToken,
                "/api/statements/disputes/" + disputeId + "/resolve",
                Map.of("resolution", "已提供烧结质检记录，诊所认可加工责任")));
        assertEquals("RESOLVED", resolved.get("status"));

        // 争议解决 → 财务端最终确认
        Map<String, Object> financeConfirmed = data(post(financeToken,
                "/api/statements/" + statementId + "/finance-confirm", null));
        assertEquals("FINANCE_CONFIRMED", financeConfirmed.get("status"));
        assertNotNull(financeConfirmed.get("financeConfirmedAt"));

        // 月结详情：订单行、返工原因分类、争议记录
        Map<String, Object> detail = data(get(doctorToken, "/api/statements/" + statementId));
        assertEquals(1, list(detail, "orders").size());
        List<Map<String, Object>> detailReworks = list(detail, "reworks");
        assertEquals("PROCESSING", detailReworks.get(0).get("cause"));
        List<Map<String, Object>> disputes = list(detail, "disputes");
        assertEquals(1, disputes.size());
        assertEquals("RESOLVED", disputes.get(0).get("status"));
    }

    @Test
    @Order(8)
    void toothBoardAndPerformance() {
        // 按牙位看板：每颗牙的设计、工序、返工责任
        Map<String, Object> board = data(get(labAdminToken, "/api/orders/" + orderId + "/tooth-board"));
        List<Map<String, Object>> teeth = list(board, "teeth");
        assertEquals(2, teeth.size());
        Map<String, Object> tooth12 = teeth.stream()
                .filter(t -> "12".equals(t.get("toothPosition"))).findFirst().orElseThrow();
        assertFalse(list(tooth12, "stages").isEmpty());
        assertEquals(1, list(tooth12, "reworks").size());
        assertEquals("LAB", list(tooth12, "reworks").get(0).get("responsibility"));
        assertEquals("PROCESSING", list(tooth12, "reworks").get(0).get("cause"));
        // 首版设计 + 返工重做设计共两个版本
        assertEquals(2, list(tooth12, "designVersions").size());

        // 技工绩效
        Map<String, Object> perf = data(get(labAdminToken, "/api/performance/technicians"));
        List<Map<String, Object>> techs = list(perf, "$data");
        assertEquals(2, techs.size());
        Map<String, Object> tech1 = techs.stream()
                .filter(t -> "张技工".equals(t.get("technicianName"))).findFirst().orElseThrow();
        assertTrue(((Number) tech1.get("stagesCompleted")).longValue() >= 5);
    }

    // ---------- 第二场景：加急 + 配送延误 + 异常处理 ----------

    @Test
    @Order(9)
    void rushOrderWithLateDelivery() {
        // 诊所下单（烤瓷冠，单牙位，难度1）
        Map<String, Object> body = new HashMap<>();
        body.put("patientCode", "PAT-2026-0002");
        body.put("prosthesisType", "PFM_CROWN");
        body.put("impressionMethod", "TRADITIONAL");
        body.put("toothPositions", List.of("36"));
        body.put("shade", "VITA B1");
        body.put("expectedTryInDate", LocalDate.now().plusDays(5).toString());
        body.put("difficulty", 1);
        Map<String, Object> order = data(post(doctorToken, "/api/orders", body));
        Long oid = toLong(order.get("id"));
        Long item = toLong(list(order, "toothItems").get(0).get("id"));

        // 取模气泡异常上报 → 客服处理
        Map<String, Object> exc = data(post(doctorToken, "/api/orders/" + oid + "/exceptions",
                Map.of("type", "IMPRESSION_BUBBLE", "description", "模型 36 远中气泡", "toothItemId", item)));
        Long excId = toLong(exc.get("id"));
        Map<String, Object> handled = data(post(serviceToken, "/api/exceptions/" + excId + "/handle",
                Map.of("resolution", "诊所重新取模后快递至技工所，已收到", "resolved", true)));
        assertEquals("RESOLVED", handled.get("status"));

        // 患者加急
        Map<String, Object> rushed = data(post(doctorToken, "/api/orders/" + oid + "/rush",
                Map.of("reason", "患者出差，需提前试戴")));
        assertEquals(Boolean.TRUE, rushed.get("rush"));

        // 快速走完工序
        data(post(receiverToken, "/api/orders/" + oid + "/accept", null));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("MODEL_RECEIVING", null, null, null, null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SCAN_DESIGN", null, null, null, null)));
        data(post(doctorToken, "/api/orders/" + oid + "/items/" + item + "/confirm", null));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("TEETH_ARRANGEMENT", null, null, null, null)));
        data(post(tech2Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SINTERING", null, null, null, null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("POLISHING", null, null, null, null)));
        data(post(qcToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("QC", null, null, null, null)));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("DELIVERY", null, null, null, null)));

        // 发货 + 延误签收 → 试戴顺延 + 自动登记配送延误异常
        Map<String, Object> shipment = data(post(receiverToken, "/api/orders/" + oid + "/shipments", null));
        Long shipmentId = toLong(shipment.get("id"));
        data(post(clinicAdminToken, "/api/shipments/" + shipmentId + "/sign",
                Map.of("signResult", "LATE", "signedByName", "陈前台", "delayReason", "暴雨高速封路")));
        Map<String, Object> detail = data(get(doctorToken, "/api/orders/" + oid));
        assertTrue(list(detail, "exceptions").stream().anyMatch(e -> "DELIVERY_DELAY".equals(e.get("type"))));

        // 试戴密合 → 完成
        Map<String, Object> fit = new HashMap<>();
        fit.put("toothItemId", item);
        fit.put("result", "FIT");
        fit.put("experienceScore", 4);
        data(post(doctorToken, "/api/orders/" + oid + "/try-ins", fit));

        // 结算：300×1×1.0 + 加急 300×0.3=90 − 延误扣罚 300×0.05=15 → 375
        Map<String, Object> s = data(post(financeToken, "/api/orders/" + oid + "/settlement", null));
        assertMoney("300.00", s.get("baseAmount"));
        assertMoney("90.00", s.get("rushFee"));
        assertMoney("15.00", s.get("deliveryPenalty"));
        assertMoney("375.00", s.get("totalAmount"));
    }

    // ---------- 第三场景：咬合高返工（收费重做，医生取模问题→诊所承担） ----------

    @Test
    @Order(10)
    void highBiteReworkChargeableDesignCause() {
        Map<String, Object> body = new HashMap<>();
        body.put("patientCode", "PAT-2026-0003");
        body.put("prosthesisType", "PFM_CROWN");
        body.put("impressionMethod", "TRADITIONAL");
        body.put("toothPositions", List.of("46"));
        body.put("shade", "VITA A3");
        body.put("expectedTryInDate", LocalDate.now().plusDays(3).toString());
        body.put("difficulty", 1);
        Map<String, Object> order = data(post(doctorToken, "/api/orders", body));
        Long oid = toLong(order.get("id"));
        Long item = toLong(list(order, "toothItems").get(0).get("id"));

        data(post(receiverToken, "/api/orders/" + oid + "/accept", null));
        for (String s : List.of("MODEL_RECEIVING", "SCAN_DESIGN")) {
            data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                    stageBody(s, null, null, null, null)));
        }
        data(post(doctorToken, "/api/orders/" + oid + "/items/" + item + "/confirm", null));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("TEETH_ARRANGEMENT", null, null, null, null)));
        data(post(tech2Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SINTERING", null, null, null, null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("POLISHING", null, null, null, null)));
        data(post(qcToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("QC", null, null, null, null)));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("DELIVERY", null, null, null, null)));
        Map<String, Object> ship = data(post(receiverToken, "/api/orders/" + oid + "/shipments", null));
        data(post(clinicAdminToken, "/api/shipments/" + toLong(ship.get("id")) + "/sign",
                Map.of("signResult", "GOOD", "signedByName", "陈前台")));

        // 试戴咬合高：必须带 misfitType=HIGH_BITE，附医生备注与试戴照片
        Map<String, Object> highBite = new HashMap<>();
        highBite.put("toothItemId", item);
        highBite.put("result", "MISFIT");
        highBite.put("misfitType", "HIGH_BITE");
        highBite.put("doctorNotes", "46 咬合高，正中咬合早接触");
        highBite.put("photos", List.of("tryin_46_bite.jpg"));
        highBite.put("experienceScore", 2);
        data(post(doctorToken, "/api/orders/" + oid + "/try-ins", highBite));

        // 待诊所确认返工列表能看到该单
        Map<String, Object> awaiting = data(get(clinicAdminToken, "/api/reworks/awaiting-confirm"));
        Map<String, Object> pendingRw = list(awaiting, "$data").stream()
                .filter(r -> oid.equals(toLong(r.get("orderId")))).findFirst().orElseThrow();
        Long rwId = toLong(pendingRw.get("id"));
        assertEquals("HIGH_BITE", pendingRw.get("misfitType"));

        // 缺少是否收费 → 确认被拒
        ResponseEntity<Map> badConfirm = postRaw(clinicAdminToken,
                "/api/reworks/" + rwId + "/clinic-confirm", Map.of("deadlineHours", 48));
        assertEquals(HttpStatus.BAD_REQUEST, badConfirm.getStatusCode());

        // 诊所确认：48 小时时限、收费重做
        Map<String, Object> confirmed = data(post(clinicAdminToken,
                "/api/reworks/" + rwId + "/clinic-confirm",
                Map.of("deadlineHours", 48, "chargeable", true, "confirmNote", "设计咬合面偏高")));
        assertEquals("CONFIRMED", confirmed.get("status"));
        assertEquals(Boolean.TRUE, confirmed.get("chargeable"));

        // 技工所标记：医生取模问题（咬合关系记录偏差）→ 责任判诊所（收费 100）
        data(post(labAdminToken, "/api/reworks/" + rwId + "/mark-cause",
                Map.of("cause", "IMPRESSION", "causeNote", "咬合关系取模偏差导致咬合高")));
        data(post(labAdminToken, "/api/reworks/" + rwId + "/assign-responsibility",
                Map.of("responsibility", "CLINIC", "reworkCost", 100)));

        // 重做、重发、试戴密合
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("MODEL_RECEIVING", null, null, null, null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SCAN_DESIGN", null, null, null, null)));
        data(post(doctorToken, "/api/orders/" + oid + "/items/" + item + "/confirm", null));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("TEETH_ARRANGEMENT", null, null, null, null)));
        data(post(tech2Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("SINTERING", null, null, null, null)));
        data(post(tech1Token, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("POLISHING", null, null, null, null)));
        data(post(qcToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("QC", null, null, null, null)));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("DELIVERY", null, null, null, null)));
        Map<String, Object> ship2 = data(post(receiverToken, "/api/orders/" + oid + "/shipments", null));
        data(post(clinicAdminToken, "/api/shipments/" + toLong(ship2.get("id")) + "/sign",
                Map.of("signResult", "GOOD", "signedByName", "陈前台")));
        data(post(doctorToken, "/api/orders/" + oid + "/try-ins",
                Map.of("toothItemId", item, "result", "FIT", "experienceScore", 5)));

        // 结算：基础 300 + 诊所承担收费返工 100 = 400
        Map<String, Object> s = data(post(financeToken, "/api/orders/" + oid + "/settlement", null));
        assertMoney("300.00", s.get("baseAmount"));
        assertMoney("100.00", s.get("reworkClinicShare"));
        assertMoney("400.00", s.get("totalAmount"));
        assertTrue(String.valueOf(s.get("detail")).contains("医生取模问题"));
    }

    // ---------- 工具方法 ----------

    private String login(String username, String password) {
        Map<String, Object> resp = data(plain.postForEntity(url("/api/auth/login"),
                Map.of("username", username, "password", password), Map.class));
        return String.valueOf(resp.get("token"));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> postRaw(String token, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set("X-Auth-Token", token);
        }
        return plain.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body == null ? Map.of() : body, headers), Map.class);
    }

    private ResponseEntity<Map> post(String token, String path, Object body) {
        ResponseEntity<Map> resp = postRaw(token, path, body);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                () -> "POST " + path + " -> " + resp.getStatusCode() + " " + resp.getBody());
        return resp;
    }

    private ResponseEntity<Map> get(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Auth-Token", token);
        ResponseEntity<Map> resp = plain.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                () -> "GET " + path + " -> " + resp.getStatusCode() + " " + resp.getBody());
        return resp;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> resp) {
        Map<String, Object> body = Objects.requireNonNull(resp.getBody());
        assertEquals(Boolean.TRUE, body.get("success"), () -> "响应失败: " + body);
        Object data = body.get("data");
        if (data instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        // 列表响应：包装一层
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("$data", data);
        return wrapper;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(Map<String, Object> map, String key) {
        return (List<Map<String, Object>>) map.get(key);
    }

    private Long toLong(Object v) {
        return ((Number) v).longValue();
    }

    private void assertMoney(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))),
                () -> "金额不符: expected=" + expected + " actual=" + actual);
    }

    private Map<String, Object> stageBody(String stage, String note, Long materialBatchId,
                                          String materialQty, List<String> photos) {
        Map<String, Object> body = new HashMap<>();
        body.put("stage", stage);
        if (note != null) {
            body.put("note", note);
        }
        body.put("estimatedFinishAt", "2026-12-31T23:59:00");
        body.put("finish", true);
        if (materialBatchId != null) {
            body.put("materialBatchId", materialBatchId);
            body.put("materialQty", materialQty);
        }
        if (photos != null) {
            body.put("photos", photos);
        }
        return body;
    }
}
