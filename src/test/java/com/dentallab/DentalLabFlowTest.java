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
 * 下单→接单→八道工序→发货→签收→试戴（密合/边缘不密合/咬合高）→
 * 返工方案（时限/责任/收费）→诊所驳回→修订→确认→标记原因(设计/加工/取模)→重做→
 * 再签收→结算→技工所同步月结→月结争议→处理→诊所确认→财务确认。
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
    private static Long disputeId;

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
        // OpenAPI 文档与 Swagger UI 可匿名访问
        assertEquals(HttpStatus.OK,
                plain.getForEntity(url("/v3/api-docs"), Map.class).getStatusCode());
        assertEquals(HttpStatus.OK,
                plain.getForEntity(url("/swagger-ui/index.html"), String.class).getStatusCode());
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

        // 技工角色不能下单
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
            // 扫描设计（技工1），写设计摘要与设计文件 → 生成设计版本 v1
            Map<String, Object> design = stageBody("SCAN_DESIGN", "设计完成", null, null,
                    List.of("design_v1_" + item + ".stl"));
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
        // 每颗牙生成设计版本 v1
        List<Map<String, Object>> versions = list(detail, "designVersions");
        assertEquals(2, versions.size());
        assertEquals(1, versions.get(0).get("versionNo"));
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

        // 不密合但未说明问题类型应被拒绝
        Map<String, Object> missingIssue = new HashMap<>();
        missingIssue.put("toothItemId", item2);
        missingIssue.put("result", "MISFIT");
        missingIssue.put("feedback", "不舒服");
        ResponseEntity<Map> bad = postRaw(doctorToken, "/api/orders/" + orderId + "/try-ins", missingIssue);
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());

        // 牙位 12 试戴边缘不密合，附医生备注与试戴照片 → 自动异常 + 自动返工（待方案）
        Map<String, Object> misfit = new HashMap<>();
        misfit.put("toothItemId", item2);
        misfit.put("result", "MISFIT");
        misfit.put("issueType", "EDGE_GAP");
        misfit.put("feedback", "边缘不密合，食物嵌塞");
        misfit.put("photos", List.of("tryin_12_1.jpg", "tryin_12_2.jpg"));
        misfit.put("experienceScore", 2);
        Map<String, Object> misfitRecord = data(post(doctorToken, "/api/orders/" + orderId + "/try-ins", misfit));
        assertEquals("EDGE_GAP", misfitRecord.get("issueType"));
        assertNotNull(misfitRecord.get("reworkId"));

        Map<String, Object> after = data(get(doctorToken, "/api/orders/" + orderId));
        List<Map<String, Object>> exceptions = list(after, "exceptions");
        assertTrue(exceptions.stream().anyMatch(e -> "TRY_IN_MISFIT".equals(e.get("type"))));
        List<Map<String, Object>> reworks = list(after, "reworks");
        assertEquals(1, reworks.size());
        Map<String, Object> rework = reworks.get(0);
        reworkId = toLong(rework.get("id"));
        // 新返工单：待技工所提交方案；关联设计版本 v1、医生备注、试戴照片
        assertEquals("AWAITING_PLAN", rework.get("status"));
        assertEquals("EDGE_GAP", rework.get("issueType"));
        assertNull(rework.get("responsibility"));
        assertNotNull(rework.get("designVersionId"));
        assertEquals(1, rework.get("designVersionNo"));
        assertNotNull(rework.get("tryInPhotos"));
        assertTrue(String.valueOf(rework.get("tryInPhotos")).contains("tryin_12_1.jpg"));

        // 返工方案未经诊所确认前，牙位不能开始重做
        ResponseEntity<Map> blockedStage = postRaw(receiverToken,
                "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("MODEL_RECEIVING", "试图先返工", null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, blockedStage.getStatusCode());
    }

    @Test
    @Order(5)
    void reworkPlanConfirmCauseAndRedo() {
        // 技工所工作台可见待方案返工
        assertEquals(1, list(data(get(labAdminToken, "/api/reworks/awaiting-plan")), "$data").size());

        // 技工所提交返工方案：48 小时时限、建议技工所责任、成本 200、不向诊所收费
        Map<String, Object> plan = data(post(labAdminToken, "/api/reworks/" + reworkId + "/plan",
                Map.of("reworkDeadlineHours", 48, "proposedResponsibility", "LAB",
                        "reworkCost", 200, "chargeClinic", false,
                        "labAction", "重新扫描设计边缘并加工")));
        assertEquals("AWAITING_CONFIRM", plan.get("status"));
        assertEquals(48, plan.get("reworkDeadlineHours"));
        assertEquals(Boolean.FALSE, plan.get("chargeClinic"));

        // 诊所工作台可见待确认方案；先驳回一次
        assertEquals(1, list(data(get(clinicAdminToken, "/api/reworks/awaiting-confirm")), "$data").size());
        Map<String, Object> rejected = data(post(clinicAdminToken,
                "/api/reworks/" + reworkId + "/clinic-reject",
                Map.of("rejectReason", "48 小时太慢，要求 24 小时内完成")));
        assertEquals("REJECTED", rejected.get("status"));

        // 非待确认状态不能确认
        ResponseEntity<Map> confirmBad = postRaw(clinicAdminToken,
                "/api/reworks/" + reworkId + "/clinic-confirm", Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, confirmBad.getStatusCode());

        // 技工所修订方案：24 小时时限
        data(post(labAdminToken, "/api/reworks/" + reworkId + "/plan",
                Map.of("reworkDeadlineHours", 24, "proposedResponsibility", "LAB",
                        "reworkCost", 200, "chargeClinic", false,
                        "labAction", "优先重做，24 小时内完成")));
        // 诊所确认返工方案（时限/责任/收费落定）
        Map<String, Object> confirmed = data(post(clinicAdminToken,
                "/api/reworks/" + reworkId + "/clinic-confirm", Map.of()));
        assertEquals("IN_REWORK", confirmed.get("status"));
        assertEquals("LAB", confirmed.get("responsibility"));
        assertEquals(Boolean.TRUE, confirmed.get("clinicConfirmed"));

        // 牙位 12 返工重做：从模型接收重新开始
        data(post(receiverToken, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("MODEL_RECEIVING", "返工模型接收", null, null, null)));
        data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("SCAN_DESIGN", "返工重新设计", null, null, List.of("design_v2_12.stl"))));
        data(post(doctorToken, "/api/orders/" + orderId + "/items/" + item2 + "/confirm", null));
        data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("TEETH_ARRANGEMENT", "返工排牙", null, null, null)));
        data(post(tech2Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("SINTERING", "返工烧结", zirconiaBatchId, "1", null)));
        data(post(tech1Token, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("POLISHING", "返工抛光", null, null, null)));

        // 返工牙位未标记原因不能通过质检
        ResponseEntity<Map> qcBlocked = postRaw(qcToken,
                "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("QC", "返工质检", null, null, null));
        assertEquals(HttpStatus.BAD_REQUEST, qcBlocked.getStatusCode());

        // 技工所标记返工原因：加工问题 → 责任落定技工所
        Map<String, Object> caused = data(post(labAdminToken,
                "/api/reworks/" + reworkId + "/cause", Map.of("cause", "PROCESSING")));
        assertEquals("PROCESSING", caused.get("cause"));
        assertEquals("LAB", caused.get("responsibility"));
        assertMoney("200.00", caused.get("labShare"));

        data(post(qcToken, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("QC", "返工质检合格", null, null, null)));
        data(post(receiverToken, "/api/orders/" + orderId + "/items/" + item2 + "/stages",
                stageBody("DELIVERY", "返工打包", null, null, null)));

        // 返工单应已自动完成
        Map<String, Object> reworks = data(get(doctorToken, "/api/orders/" + orderId + "/reworks"));
        Map<String, Object> rework = list(reworks, "$data").get(0);
        assertEquals("COMPLETED", rework.get("status"));

        // 返工重设计生成设计版本 v2 并关联返工单
        Map<String, Object> board = data(get(labAdminToken, "/api/orders/" + orderId + "/tooth-board"));
        List<Map<String, Object>> teeth = list(board, "teeth");
        Map<String, Object> tooth12 = teeth.stream()
                .filter(t -> "12".equals(t.get("toothPosition"))).findFirst().orElseThrow();
        List<Map<String, Object>> versions = list(tooth12, "designVersions");
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(1).get("versionNo"));
        assertEquals(reworkId, toLong(versions.get(1).get("reworkId")));

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

        // 返工方案未确认时不能结算——此处责任已由加工问题落定，可直接结算（拦截场景见场景二）
        data(post(financeToken, "/api/orders/" + orderId + "/settlement", null));
    }

    @Test
    @Order(6)
    void settlementReflectsReworkResponsibility() {
        Map<String, Object> s = data(get(financeToken, "/api/orders/" + orderId + "/settlement"));
        // 基础费 500×2=1000，难度3系数1.30 → 1300；技工所承担返工(加工问题) 200 扣减 → 1100
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
    void monthlyStatementSyncDisputeAndConfirm() {
        String ym = java.time.YearMonth.now().toString();
        // 财务生成月结单
        Map<String, Object> statement = data(post(financeToken, "/api/statements/generate",
                Map.of("clinicId", 1, "yearMonth", ym)));
        statementId = toLong(statement.get("id"));
        assertEquals("GENERATED", statement.get("status"));
        assertEquals(1, ((Number) statement.get("unreviewedReworkCount")).intValue());
        assertMoney("1100.00", statement.get("totalAmount"));
        assertMoney("200.00", statement.get("reworkLabDeduction"));

        // 未同步时诊所确认被拒；未诊所确认时财务确认被拒
        ResponseEntity<Map> earlyConfirm = postRaw(clinicAdminToken,
                "/api/statements/" + statementId + "/confirm", Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, earlyConfirm.getStatusCode());
        ResponseEntity<Map> earlyFinance = postRaw(financeToken,
                "/api/statements/" + statementId + "/finance-confirm", Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, earlyFinance.getStatusCode());

        // 技工所同步月结单给诊所
        Map<String, Object> synced = data(post(labAdminToken,
                "/api/statements/" + statementId + "/sync", Map.of()));
        assertEquals("SYNCED", synced.get("status"));

        // 同步后但返工未复核，诊所确认仍被拒
        ResponseEntity<Map> reviewBlocked = postRaw(clinicAdminToken,
                "/api/statements/" + statementId + "/confirm", Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, reviewBlocked.getStatusCode());

        // 诊所发起月结争议（关联返工单）→ 争议中
        Map<String, Object> dispute = data(post(clinicAdminToken,
                "/api/statements/" + statementId + "/dispute",
                Map.of("reason", "对加工问题责任认定有异议", "reworkId", reworkId)));
        disputeId = toLong(dispute.get("id"));
        assertEquals("OPEN", dispute.get("status"));

        // 争议中不能确认月结
        ResponseEntity<Map> confirmInDispute = postRaw(clinicAdminToken,
                "/api/statements/" + statementId + "/confirm", Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, confirmInDispute.getStatusCode());

        // 技工所处理争议 → 月结单重新同步诊所
        Map<String, Object> resolved = data(post(labAdminToken,
                "/api/statements/disputes/" + disputeId + "/resolve",
                Map.of("resolution", "经核对加工记录确认为加工问题，维持技工所承担200")));
        assertEquals("RESOLVED", resolved.get("status"));

        // 月结单详情含争议记录与返工调整明细
        Map<String, Object> detail = data(get(labAdminToken, "/api/statements/" + statementId));
        assertEquals("SYNCED", detail.get("status"));
        assertEquals(1, ((Number) detail.get("disputeCount")).intValue());
        assertEquals(1, list(detail, "disputes").size());
        assertEquals(1, list(detail, "reworks").size());

        // 诊所复核返工 → 确认月结 → 财务确认
        assertEquals(1, list(data(get(clinicAdminToken, "/api/reworks/unreviewed")), "$data").size());
        data(post(clinicAdminToken, "/api/reworks/" + reworkId + "/clinic-review", null));
        Map<String, Object> confirmed = data(post(clinicAdminToken,
                "/api/statements/" + statementId + "/confirm", Map.of()));
        assertEquals("CLINIC_CONFIRMED", confirmed.get("status"));
        Map<String, Object> financeConfirmed = data(post(financeToken,
                "/api/statements/" + statementId + "/finance-confirm", Map.of()));
        assertEquals("FINANCE_CONFIRMED", financeConfirmed.get("status"));
        assertNotNull(financeConfirmed.get("financeConfirmedAt"));

        // 终态不能重复确认
        ResponseEntity<Map> doubleConfirm = postRaw(financeToken,
                "/api/statements/" + statementId + "/finance-confirm", Map.of());
        assertEquals(HttpStatus.BAD_REQUEST, doubleConfirm.getStatusCode());
    }

    @Test
    @Order(8)
    void toothBoardAndPerformance() {
        // 按牙位看板：每颗牙的设计版本、工序、返工责任/原因
        Map<String, Object> board = data(get(labAdminToken, "/api/orders/" + orderId + "/tooth-board"));
        List<Map<String, Object>> teeth = list(board, "teeth");
        assertEquals(2, teeth.size());
        Map<String, Object> tooth12 = teeth.stream()
                .filter(t -> "12".equals(t.get("toothPosition"))).findFirst().orElseThrow();
        assertFalse(list(tooth12, "stages").isEmpty());
        assertEquals(1, list(tooth12, "reworks").size());
        assertEquals("LAB", list(tooth12, "reworks").get(0).get("responsibility"));
        assertEquals("PROCESSING", list(tooth12, "reworks").get(0).get("cause"));

        // 技工绩效
        Map<String, Object> perf = data(get(labAdminToken, "/api/performance/technicians"));
        List<Map<String, Object>> techs = list(perf, "$data");
        assertEquals(2, techs.size());
        Map<String, Object> tech1 = techs.stream()
                .filter(t -> "张技工".equals(t.get("technicianName"))).findFirst().orElseThrow();
        assertTrue(((Number) tech1.get("stagesCompleted")).longValue() >= 5);
    }

    // ---------- 第二场景：加急 + 配送延误 + 咬合高返工 ----------

    @Test
    @Order(9)
    void rushOrderWithLateDeliveryAndHighBiteRework() {
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
        produceAll(oid, item, false);

        // 发货 + 延误签收 → 试戴顺延 + 自动登记配送延误异常
        Map<String, Object> shipment = data(post(receiverToken, "/api/orders/" + oid + "/shipments", null));
        Long shipmentId = toLong(shipment.get("id"));
        data(post(clinicAdminToken, "/api/shipments/" + shipmentId + "/sign",
                Map.of("signResult", "LATE", "signedByName", "陈前台", "delayReason", "暴雨高速封路")));
        Map<String, Object> detail = data(get(doctorToken, "/api/orders/" + oid));
        assertTrue(list(detail, "exceptions").stream().anyMatch(e -> "DELIVERY_DELAY".equals(e.get("type"))));

        // 试戴咬合高（HIGH_BITE，issueType 可省略，默认咬合高）→ 自动返工
        Map<String, Object> high = new HashMap<>();
        high.put("toothItemId", item);
        high.put("result", "HIGH_BITE");
        high.put("feedback", "正中咬合早接触，咬合高");
        high.put("photos", List.of("tryin_36_bite.jpg"));
        Map<String, Object> highRecord = data(post(doctorToken, "/api/orders/" + oid + "/try-ins", high));
        assertEquals("HIGH_BITE", highRecord.get("issueType"));
        Long rwId = toLong(highRecord.get("reworkId"));

        // 返工方案 → 诊所确认为医生取模问题（向诊所收费 100）→ 重做时标记 IMPRESSION
        data(post(labAdminToken, "/api/reworks/" + rwId + "/plan",
                Map.of("reworkDeadlineHours", 24, "proposedResponsibility", "CLINIC",
                        "reworkCost", 100, "chargeClinic", true, "labAction", "根据新模型重新制作")));
        data(post(clinicAdminToken, "/api/reworks/" + rwId + "/clinic-confirm", Map.of()));

        produceAll(oid, item, true);
        // 返工重做质检前标记：医生取模问题 → 诊所承担
        Map<String, Object> cause = data(post(labAdminToken,
                "/api/reworks/" + rwId + "/cause", Map.of("cause", "IMPRESSION")));
        assertEquals("CLINIC", cause.get("responsibility"));
        assertMoney("100.00", cause.get("clinicShare"));

        // 标记原因后登记返工质检与配送工序
        data(post(qcToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("QC", "返工质检合格", null, null, null)));
        data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                stageBody("DELIVERY", "返工打包", null, null, null)));

        // 重发 + 完好签收 + 试戴密合 → 完成
        Map<String, Object> ship2 = data(post(receiverToken, "/api/orders/" + oid + "/shipments", null));
        data(post(clinicAdminToken, "/api/shipments/" + toLong(ship2.get("id")) + "/sign",
                Map.of("signResult", "GOOD", "signedByName", "陈前台")));
        Map<String, Object> fit = new HashMap<>();
        fit.put("toothItemId", item);
        fit.put("result", "FIT");
        fit.put("experienceScore", 4);
        data(post(doctorToken, "/api/orders/" + oid + "/try-ins", fit));
        assertEquals("COMPLETED",
                data(get(doctorToken, "/api/orders/" + oid)).get("status"));

        // 结算：300×1×1.0=300，加急 90，诊所承担取模返工 100，延误扣罚 15 → 475
        Map<String, Object> s = data(post(financeToken, "/api/orders/" + oid + "/settlement", null));
        assertMoney("300.00", s.get("baseAmount"));
        assertMoney("90.00", s.get("rushFee"));
        assertMoney("100.00", s.get("reworkClinicShare"));
        assertMoney("15.00", s.get("deliveryPenalty"));
        assertMoney("475.00", s.get("totalAmount"));
    }

    // ---------- 工具方法 ----------

    /**
     * 单牙位走工序；rework=false 走完全部八道（含接单/质检/配送），
     * rework=true 为返工重做：不接单，止于抛光（质检前需先标记返工原因）。
     */
    private void produceAll(Long oid, Long item, boolean rework) {
        if (!rework) {
            data(post(receiverToken, "/api/orders/" + oid + "/accept", null));
        }
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
        if (!rework) {
            data(post(qcToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                    stageBody("QC", null, null, null, null)));
            data(post(receiverToken, "/api/orders/" + oid + "/items/" + item + "/stages",
                    stageBody("DELIVERY", null, null, null, null)));
        }
    }

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
        assertNotNull(actual, "金额为空: expected=" + expected);
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
