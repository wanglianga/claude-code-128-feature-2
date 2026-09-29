package com.dentallab.web;

import com.dentallab.model.MisfitType;
import com.dentallab.model.TryInResult;
import com.dentallab.service.TryInService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 试戴结果（咬合高/边缘不密合）、医生备注与试戴照片。 */
@RestController
@RequestMapping("/api/orders/{orderId}/try-ins")
public class TryInController {

    private final TryInService tryInService;

    public TryInController(TryInService tryInService) {
        this.tryInService = tryInService;
    }

    /**
     * 记录试戴结果，可针对单个牙位或整单：
     * result=FIT 密合 / MISFIT 不密合（需 issueType=EDGE_GAP 边缘不密合/BOTH）/ HIGH_BITE 咬合高；
     * feedback 医生备注；photos 试戴照片。
     */
    @PostMapping
    public Map<String, Object> record(HttpServletRequest request, @PathVariable Long orderId,
                                      @RequestBody Map<String, Object> body) {
        return Web.ok(tryInService.recordResult(Web.user(request), orderId,
                Req.longVal(body, "toothItemId"),
                Req.enumReq(body, "result", TryInResult.class, "试戴结果"),
                Req.enumVal(body, "issueType", MisfitType.class),
                Req.str(body, "feedback"),
                Req.strList(body, "photos"),
                Req.intVal(body, "experienceScore")));
    }

    @GetMapping
    public Map<String, Object> list(@PathVariable Long orderId) {
        return Web.ok(tryInService.listByOrder(orderId));
    }
}
