package com.dentallab.web;

import com.dentallab.service.StatementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 诊所月结：财务生成 → 技工所同步诊所 → 诊所确认 → 财务确认；
 * 诊所可发起争议，争议同步记录并在处理后重新同步。
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

    /** 技工所把月结单同步给诊所。 */
    @PostMapping("/{id}/sync")
    public Map<String, Object> sync(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(statementService.syncToClinic(Web.user(request), id));
    }

    /** 诊所确认月结（账期内返工须全部复核）。 */
    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(statementService.confirm(Web.user(request), id));
    }

    /** 财务端确认月结。 */
    @PostMapping("/{id}/finance-confirm")
    public Map<String, Object> financeConfirm(HttpServletRequest request, @PathVariable Long id) {
        return Web.ok(statementService.financeConfirm(Web.user(request), id));
    }

    /** 诊所发起月结争议（可关联返工单）。 */
    @PostMapping("/{id}/dispute")
    public Map<String, Object> dispute(HttpServletRequest request, @PathVariable Long id,
                                       @RequestBody Map<String, Object> body) {
        return Web.ok(statementService.raiseDispute(Web.user(request), id,
                Req.strReq(body, "reason", "争议原因"),
                Req.longVal(body, "reworkId")));
    }

    /** 技工所/财务处理月结争议。 */
    @PostMapping("/disputes/{disputeId}/resolve")
    public Map<String, Object> resolveDispute(HttpServletRequest request, @PathVariable Long disputeId,
                                              @RequestBody Map<String, Object> body) {
        return Web.ok(statementService.resolveDispute(Web.user(request), disputeId,
                Req.strReq(body, "resolution", "处理结论")));
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(required = false) Long clinicId) {
        return Web.ok(statementService.list(Web.user(request), clinicId));
    }

    /** 月结单详情（含争议记录与返工调整明细）。 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return Web.ok(statementService.detail(id));
    }
}
