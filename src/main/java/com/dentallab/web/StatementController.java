package com.dentallab.web;

import com.dentallab.service.StatementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 诊所月结：财务生成 → 技工所同步 → 诊所确认 → 财务确认；月结争议双向同步。
 */
@RestController
@RequestMapping("/api/statements")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    /** 财务生成诊所月结单。 */
    @PostMapping("/generate")
    public Map<String, Object> generate(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        return Web.ok(statementService.generate(Web.user(request),
                Req.longReq(body, "clinicId", "诊所ID"),
                Req.strReq(body, "yearMonth", "账期")));
    }

    /** 技工所同步月结单（同步后诊所可见并可确认）。 */
    @PostMapping("/{id}/lab-sync")
    public Map<String, Object> labSync(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(statementService.labSync(Web.user(request), id));
    }

    /** 诊所确认月结（技工所同步后；确认前必须复核全部返工）。 */
    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(statementService.confirm(Web.user(request), id));
    }

    /** 财务端最终确认（无未解决月结争议时）。 */
    @PostMapping("/{id}/finance-confirm")
    public Map<String, Object> financeConfirm(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(statementService.financeConfirm(Web.user(request), id));
    }

    /** 月结单详情：订单结算行、返工责任调整、争议记录。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(statementService.detail(Web.user(request), id));
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(required = false) Long clinicId) {
        return Web.ok(statementService.list(Web.user(request), clinicId));
    }

    /** 提出月结争议（诊所/技工所双方均可）。 */
    @PostMapping("/{id}/disputes")
    public Map<String, Object> raiseDispute(HttpServletRequest request, @PathVariable Long id,
                                            @RequestBody Map<String, Object> body) {
        return Web.ok(statementService.raiseDispute(Web.user(request), id,
                Req.strReq(body, "description", "争议说明")));
    }

    /** 处理月结争议。 */
    @PostMapping("/disputes/{disputeId}/resolve")
    public Map<String, Object> resolveDispute(HttpServletRequest request, @PathVariable Long disputeId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        String resolution = body == null ? null : Req.str(body, "resolution");
        return Web.ok(statementService.resolveDispute(Web.user(request), disputeId, resolution));
    }

    /** 月结单下的争议记录。 */
    @GetMapping("/{id}/disputes")
    public Map<String, Object> listDisputes(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(statementService.listDisputes(Web.user(request), id));
    }

    /** 未解决月结争议工作台。 */
    @GetMapping("/disputes/open")
    public Map<String, Object> openDisputes(HttpServletRequest request) {
        return Web.ok(statementService.listOpenDisputes(Web.user(request)));
    }
}
