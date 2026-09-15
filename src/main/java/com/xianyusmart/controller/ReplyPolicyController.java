package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.ReplyPolicySimulationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/reply-policy")
public class ReplyPolicyController {
    private final ReplyPolicySimulationService service;
    public ReplyPolicyController(ReplyPolicySimulationService service){this.service=service;}
    @PostMapping("/simulate")
    public ResultObject<Map<String,Object>> simulate(@RequestBody ReplyPolicySimulationService.Command command){
        return ResultObject.success(service.simulate(command));
    }
}
