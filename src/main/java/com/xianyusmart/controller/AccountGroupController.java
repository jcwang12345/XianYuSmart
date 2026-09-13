package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.service.AccountGroupService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/account-groups")
public class AccountGroupController {
    private final AccountGroupService service;
    public AccountGroupController(AccountGroupService service) { this.service=service; }

    @GetMapping
    public ResultObject<List<Map<String,Object>>> list(){return ResultObject.success(service.list());}
    @PostMapping
    public ResultObject<Map<String,Object>> save(@RequestBody AccountGroupService.GroupCommand command){return ResultObject.success(service.save(command));}
    @PutMapping("/{groupId}/members")
    public ResultObject<Void> members(@PathVariable Long groupId,@RequestBody AccountGroupService.MemberCommand command){service.replaceMembers(groupId,command);return ResultObject.success(null);}
    @DeleteMapping("/{groupId}")
    public ResultObject<Void> delete(@PathVariable Long groupId,@RequestParam String requestId){service.delete(groupId,requestId);return ResultObject.success(null);}
}
