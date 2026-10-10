package com.xianyusmart.controller;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.QaHistoricalAcceptanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@Profile("qa")
@ConditionalOnProperty(name="app.r03.qa.enabled",havingValue="true")
@RequestMapping("/api/qa/r03")
public class QaHistoricalAcceptanceController {
    private final QaHistoricalAcceptanceService qa;
    public QaHistoricalAcceptanceController(QaHistoricalAcceptanceService qa){this.qa=qa;}
    @PostMapping("/fixtures") public ResultObject<Map<String,Object>> fixtures(@RequestBody Fixture request){
        if("SIGNED_PRODUCT_EVENT".equals(request.mode()))return ResultObject.success(qa.signedEvent(request.accountId(),request.eventId(),request.goodsId(),request.occurredAt(),request.tenantId(),request.timestamp(),request.signature(),request.before(),request.after()));
        if(request.mode()!=null&&!"PRODUCTS".equals(request.mode()))throw new com.xianyusmart.exception.BusinessException(400,"QA夹具模式无效");
        return ResultObject.success(qa.fixtures(request.accountId(),request.requestId(),request.count()));
    }
    @PostMapping("/faults") public ResultObject<Map<String,Object>> faults(@RequestBody Fault request,HttpServletRequest servlet){return ResultObject.success(qa.configure(request.accountId(),request.requestId(),request.operation(),request.mode(),request.calls(),request.delayMs(),servlet));}
    @GetMapping("/evidence/{requestId}") public ResultObject<Map<String,Object>> evidence(@PathVariable String requestId,HttpServletRequest servlet){return ResultObject.success(qa.evidence(requestId,servlet));}
    public record Fixture(Long accountId,String requestId,int count,String mode,String eventId,String goodsId,String occurredAt,
                          Long tenantId,Long timestamp,String signature,Map<String,Object> before,Map<String,Object> after){}
    public record Fault(Long accountId,String requestId,String operation,String mode,int calls,int delayMs){}
}
