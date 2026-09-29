package com.dentallab.web;

import com.dentallab.model.Responsibility;
import com.dentallab.model.ReworkCause;
import com.dentallab.service.ReworkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 返工：创建、诊所确认（时限/是否收费）、技工所原因标记、责任判定、诊所复核。 */
@RestController
@RequestMapping("/api")
public class ReworkController {

    private final ReworkService reworkService;

    public ReworkController(ReworkService reworkService) {
        this.reworkService = reworkService;
    }

    /** 对某个牙位人工创建返工单（生成后为“待诊所确认”）。 */
    @PostMapping("/orders/{orderId}/items/{itemId}/reworks")
    public Map<String, Object> create(HttpServletRequest request,
                                      @PathVariable Long orderId, @PathVariable Long itemId,
                                      @RequestBody Map<String, Object> body) {
        return Web.ok(reworkService.create(Web.user(request), orderId, itemId,
                Req.strReq(body, "reason", "返工原因")));
    }

    /** 诊所确认返工：返工时限 deadlineHours、是否收费 chargeable、确认备注。 */
    @PostMapping("/reworks/{id}/clinic-confirm")
    public Map<String, Object> clinicConfirm(HttpServletRequest request, @PathVariable Long id,
                                             @RequestBody Map<String, Object> body) {
        return Web.ok(reworkService.clinicConfirm(Web.user(request), id,
                Req.intVal(body, "deadlineHours"),
                Req.boolVal(body, "chargeable"),
                Req.str(body, "confirmNote")));
    }

    /** 诊所驳回返工（有异议，需线下协商）。 */
    @PostMapping("/reworks/{id}/clinic-reject")
    public Map<String, Object> clinicReject(HttpServletRequest request, @PathVariable Long id,
                                            @RequestBody Map<String, Object> body) {
        return Web.ok(reworkService.clinicReject(Web.user(request), id,
                Req.str(body, "rejectReason")));
    }

    /** 返工确认后技工所标记原因：DESIGN 设计问题 / PROCESSING 加工问题 / IMPRESSION 医生取模问题。 */
    @PostMapping("/reworks/{id}/mark-cause")
    public Map<String, Object> markCause(HttpServletRequest request, @PathVariable Long id,
                                         @RequestBody Map<String, Object> body) {
        return Web.ok(reworkService.markCause(Web.user(request), id,
                Req.enumReq(body, "cause", ReworkCause.class, "返工原因"),
                Req.str(body, "causeNote")));
    }

    /** 判定返工责任与成本（须先标记原因三分类）。 */
    @PostMapping("/reworks/{id}/assign-responsibility")
    public Map<String, Object> assign(HttpServletRequest request, @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        return Web.ok(reworkService.assignResponsibility(Web.user(request), id,
                Req.enumReq(body, "responsibility", Responsibility.class, "责任归属"),
                Req.decimal(body, "reworkCost")));
    }

    /** 诊所复核返工（对账前必须全部复核）。 */
    @PostMapping("/reworks/{id}/clinic-review")
    public Map<String, Object> clinicReview(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(reworkService.clinicReview(Web.user(request), id));
    }

    @GetMapping("/reworks/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return Web.ok(reworkService.get(id));
    }

    @GetMapping("/orders/{orderId}/reworks")
    public Map<String, Object> listByOrder(@PathVariable Long orderId) {
        return Web.ok(reworkService.listByOrder(orderId));
    }

    /** 本诊所待确认返工（确认返工时限/是否收费入口）。 */
    @GetMapping("/reworks/awaiting-confirm")
    public Map<String, Object> awaitingConfirm(HttpServletRequest request) {
        return Web.ok(reworkService.listAwaitingConfirm(Web.user(request)));
    }

    /** 本诊所待复核返工。 */
    @GetMapping("/reworks/unreviewed")
    public Map<String, Object> unreviewed(HttpServletRequest request) {
        return Web.ok(reworkService.listUnreviewed(Web.user(request)));
    }
}
