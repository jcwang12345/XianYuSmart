package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.BusinessAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/business-analytics")
public class BusinessAnalyticsController {
    private final BusinessAnalyticsService service;
    public BusinessAnalyticsController(BusinessAnalyticsService service){this.service=service;}

    @GetMapping("/overview")
    public ResultObject<Map<String,Object>> overview(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required=false) Long accountId,@RequestParam(required=false) Long groupId){
        return ResultObject.success(service.overview(start,end,accountId,groupId));
    }

    @PostMapping("/refresh-local")
    public ResultObject<Map<String,Object>> refresh(
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam String requestId){return ResultObject.success(service.refreshLocal(start,end,requestId));}
}
