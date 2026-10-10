package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.PdcaProgressService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/pdca")
public class PdcaProgressController {
    private final PdcaProgressService progress;
    public PdcaProgressController(PdcaProgressService progress){this.progress=progress;}
    @GetMapping("/overview")
    public ResultObject<Map<String,Object>> overview(HttpServletRequest request,HttpServletResponse response){noStore(response);Map<String,String> query=parameters(request,Set.of("knownRevision"));return ResultObject.success(progress.overview(query.get("knownRevision")));}
    @GetMapping("/items")
    public ResultObject<Map<String,Object>> items(HttpServletRequest request,HttpServletResponse response){noStore(response);return ResultObject.success(progress.items(parameters(request,Set.of("type","ownerRole","batch","state","severity","keyword","page","pageSize","knownRevision"))));}
    private Map<String,String> parameters(HttpServletRequest request,Set<String> allowed){var result=new LinkedHashMap<String,String>();request.getParameterMap().forEach((k,v)->{if(!allowed.contains(k)||v==null||v.length!=1)throw new BusinessException(400,"PDCA查询参数无效");result.put(k,v[0]);});return result;}
    private void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");}
}
