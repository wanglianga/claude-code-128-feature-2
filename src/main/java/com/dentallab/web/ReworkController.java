package com.dentallab.web;

import com.dentallab.model.Responsibility;
import com.dentallab.model.ReworkCause;
import com.dentallab.service.ReworkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/** 试戴不密合返工：方案提交、诊所确认/驳回、原因标记、责任判定与诊所复核。 */
@RestController
@RequestMapping("/api")
public class ReworkController {

    private final ReworkService reworkService;

    public ReworkController(ReworkService reworkService) {
        this.reworkService = reworkService;
    }

    /** 对某个牙位创建返工单（配送破损/质检等场景；试戴不密合由系统自动创建）。 */
    @PostMapping("/orders/{orderId}/items/{itemId}/reworks")
    public Map<String, Object> create(HttpServletRequest request,
                                      @PathVariable Long orderId, @PathVariable Long itemId,
                                      @RequestBody Map<String, Object> body) {
        return Web.ok(reworkService.create(Web.user(request), orderId, itemId,
                Req.strReq(body, "reason", "返工原因")));
    }

    /** 技工所提交返工方案：返工时限（小时）、建议责任、返工成本、是否收费、技工操作说明。 */
    @PostMapping("/reworks/{id}/plan")
    public Map<String, Object> submitPlan(HttpServletRequest request, @PathVariable Long id,
                                          @RequestBody Map<String, Object> body) {
        Responsibility proposed = Req.enumReq(body, "proposedResponsibility", Responsibility.class, "建议责任");
        BigDecimal cost = Req.decimal(body, "reworkCost");
        return Web.ok(reworkService.submitPlan(Web.user(request), id,
                Req.intVal(body, "reworkDeadlineHours"),
                proposed,
                cost == null ? BigDecimal.ZERO : cost,
                Req.bool(body, "chargeClinic", false),
                Req.str(body, "labAction")));
    }

    /** 诊所确认返工方案（返工时限、责任判断、是否收费）。 */
    @PostMapping("/reworks/{id}/clinic-confirm")
    public Map<String, Object> clinicConfirm(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(reworkService.clinicConfirm(Web.user(request), id));
    }

    /** 诊所驳回返工方案（需 rejectReason）。 */
    @PostMapping("/reworks/{id}/clinic-reject")
    public Map<String, Object> clinicReject(HttpServletRequest request, @PathVariable Long id,
                                            @RequestBody Map<String, Object> body) {
        return Web.ok(reworkService.clinicReject(Web.user(request), id,
                Req.strReq(body, "rejectReason", "驳回原因")));
    }

    /** 返工确认后技工所标记返工原因：DESIGN 设计问题 / PROCESSING 加工问题 / IMPRESSION 医生取模问题。 */
    @PostMapping("/reworks/{id}/cause")
    public Map<String, Object> markCause(HttpServletRequest request, @PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        return Web.ok(reworkService.markCause(Web.user(request), id,
                Req.enumReq(body, "cause", ReworkCause.class, "返工原因")));
    }

    /** 管理员/客服判定返工责任与成本（争议裁决）。 */
    @PostMapping("/reworks/{id}/assign-responsibility")
    public Map<String, Object> assign(HttpServletRequest request, @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        return Web.ok(reworkService.assignResponsibility(Web.user(request), id,
                Req.enumReq(body, "responsibility", Responsibility.class, "责任归属"),
                Req.decimal(body, "reworkCost")));
    }

    /** 诊所复核返工（月结前必须全部复核）。 */
    @PostMapping("/reworks/{id}/clinic-review")
    public Map<String, Object> clinicReview(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(reworkService.clinicReview(Web.user(request), id));
    }

    @GetMapping("/orders/{orderId}/reworks")
    public Map<String, Object> listByOrder(@PathVariable Long orderId) {
        return Web.ok(reworkService.listByOrder(orderId));
    }

    /** 技工所：待提交/被驳回待修订返工方案。 */
    @GetMapping("/reworks/awaiting-plan")
    public Map<String, Object> awaitingPlan(HttpServletRequest request) {
        return Web.ok(reworkService.listAwaitingPlan(Web.user(request)));
    }

    /** 诊所：待确认返工方案。 */
    @GetMapping("/reworks/awaiting-confirm")
    public Map<String, Object> awaitingConfirm(HttpServletRequest request) {
        return Web.ok(reworkService.listAwaitingConfirm(Web.user(request)));
    }

    /** 诊所：待复核返工（月结前）。 */
    @GetMapping("/reworks/unreviewed")
    public Map<String, Object> unreviewed(HttpServletRequest request) {
        return Web.ok(reworkService.listUnreviewed(Web.user(request)));
    }
}
