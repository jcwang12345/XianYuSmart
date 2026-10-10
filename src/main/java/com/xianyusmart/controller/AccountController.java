package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.entity.XianyuAccount;
import com.xianyusmart.mapper.XianyuAccountMapper;
import com.xianyusmart.controller.dto.AccountReqDTO;
import com.xianyusmart.controller.dto.AddAccountRespDTO;
import com.xianyusmart.controller.dto.DeleteAccountReqDTO;
import com.xianyusmart.controller.dto.DeleteAccountRespDTO;
import com.xianyusmart.controller.dto.GetAccountDetailReqDTO;
import com.xianyusmart.controller.dto.GetAccountDetailRespDTO;
import com.xianyusmart.controller.dto.GetAccountListRespDTO;
import com.xianyusmart.controller.dto.ManualAddAccountReqDTO;
import com.xianyusmart.controller.dto.UpdateAccountReqDTO;
import com.xianyusmart.controller.dto.UpdateAccountRespDTO;
import com.xianyusmart.service.AccountService;
import com.xianyusmart.service.AccountBrowserProfileService;
import com.xianyusmart.service.WebSocketService;
import com.xianyusmart.utils.XianyuSignUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.xianyusmart.service.AccountLifecycleService;
import com.xianyusmart.exception.BusinessException;

/**
 * 账号管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/account")
public class AccountController {

    @Autowired
    private XianyuAccountMapper accountMapper;

    @Autowired
    private AccountLifecycleService accountLifecycleService;
    
    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountBrowserProfileService accountBrowserProfileService;

    @Autowired
    private WebSocketService webSocketService;

    /**
     * 获取账号列表
     */
    @PostMapping("/list")
    public ResultObject<GetAccountListRespDTO> getAccountList() {
        try {
            List<XianyuAccount> accounts = new java.util.ArrayList<>(accountMapper.selectList(null));
            accounts.removeIf(account->{var local=accountLifecycleService.read(account.getId());account.setLifecycleState(String.valueOf(local.get("lifecycleState")));account.setRowVersion(((Number)local.get("rowVersion")).longValue());return "ARCHIVED".equals(account.getLifecycleState());});
            accounts.forEach(accountBrowserProfileService::decorate);
            GetAccountListRespDTO respDTO = new GetAccountListRespDTO();
            respDTO.setAccounts(accounts);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取账号列表失败", e);
            return ResultObject.failed("获取账号列表失败: " + e.getMessage());
        }
    }

    /**
     * 添加账号
     */
    @PostMapping("/add")
    public ResultObject<AddAccountRespDTO> addAccount(@RequestBody AccountReqDTO reqDTO) {
        try {
            log.info("添加账号请求: accountNote={}", reqDTO.getAccountNote());
            
            if (reqDTO.getCookie() == null || reqDTO.getCookie().isEmpty()) {
                return ResultObject.failed("Cookie不能为空");
            }
            
            Long accountId = accountService.saveAccountAndCookie(
                    reqDTO.getAccountNote(),
                    reqDTO.getUnb(),
                    reqDTO.getCookie()
            );
            accountBrowserProfileService.getOrCreate(accountId);
            
            AddAccountRespDTO respDTO = new AddAccountRespDTO();
            respDTO.setAccountId(accountId);
            respDTO.setMessage("添加成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("添加账号失败", e);
            return ResultObject.failed("添加账号失败: " + e.getMessage());
        }
    }

    /**
     * 手动添加账号
     */
    @PostMapping("/manualAdd")
    public ResultObject<AddAccountRespDTO> manualAddAccount(@RequestBody ManualAddAccountReqDTO reqDTO) {
        try {
            log.info("手动添加账号请求: accountNote={}", reqDTO.getAccountNote());
            
            if (reqDTO.getCookie() == null || reqDTO.getCookie().isEmpty()) {
                return ResultObject.failed("Cookie不能为空");
            }
            
            // 同时兼容Cookie中的unb和havana登录账号标识。
            String unb = XianyuSignUtils.extractUserId(reqDTO.getCookie());
            if (unb == null || unb.isEmpty()) {
                return ResultObject.failed("无法从Cookie中识别账号信息，请确认包含unb或有效的havana_lgc2字段");
            }
            String normalizedCookie = XianyuSignUtils.normalizeCookieUserId(
                    reqDTO.getCookie(), unb);
            
            // 检查账号是否已存在
            Long existingAccountId = accountService.getAccountIdByUnb(unb);
            if (existingAccountId != null) {
                return ResultObject.failed("账号已存在");
            }
            
            // 保存账号和Cookie信息
            Long accountId = accountService.saveAccountAndCookie(
                    reqDTO.getAccountNote(),
                    unb,
                    normalizedCookie
            );
            accountBrowserProfileService.getOrCreate(accountId);
            
            AddAccountRespDTO respDTO = new AddAccountRespDTO();
            respDTO.setAccountId(accountId);
            respDTO.setMessage("添加成功");
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("手动添加账号失败", e);
            return ResultObject.failed("添加账号失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新账号
     */
    @GetMapping("/lifecycle")
    public ResultObject<Map<String,Object>> lifecycle(@RequestParam Long accountId) {
        return ResultObject.success(accountLifecycleService.read(accountId));
    }

    @PostMapping("/update")
    public ResultObject<Map<String,Object>> updateAccount(@RequestBody JsonNode input) {
        return ResultObject.success(accountLifecycleService.command(input,false));
    }

    @PostMapping("/lifecycle")
    public ResultObject<Map<String,Object>> changeLifecycle(@RequestBody JsonNode input) {
        Map<String,Object> result=accountLifecycleService.command(input,true);
        if(!Boolean.TRUE.equals(result.get("replayed"))
                && ((List<?>)result.get("changedFields")).contains("lifecycleState")) {
            var profile=(Map<?,?>)result.get("profile");
            String state=(String)profile.get("lifecycleState");
            Long id=((Number)profile.get("accountId")).longValue();
            String outcome;
            if(!"ACTIVE".equals(state)) {
                try { outcome=webSocketService.stopWebSocket(id)?"STOPPED":"STOP_PENDING"; }
                catch(RuntimeException failure) { outcome="STOP_FAILED"; }
            } else {
                int platformStatus=profile.get("platformStatus") instanceof Number number?number.intValue():0;
                if(platformStatus==-1 || platformStatus==-2) outcome="VERIFICATION_REQUIRED";
                else {
                    try { outcome=webSocketService.startWebSocket(id) && webSocketService.isConnected(id)?"CONNECTED":"DISCONNECTED"; }
                    catch(RuntimeException failure) { outcome="DISCONNECTED"; }
                }
            }
            accountLifecycleService.recordConnectionOutcome(result,outcome);
        }
        return ResultObject.success(result);
    }

    /**
     * 删除账号
     */
    @GetMapping("/delete-preflight")
    public ResultObject<Map<String,Object>> deletePreflight(@RequestParam Long accountId) {
        return ResultObject.success(accountLifecycleService.deletePreflight(accountId));
    }

    @PostMapping("/delete")
    public ResultObject<Map<String,Object>> deleteAccount(@RequestBody JsonNode input) {
        var result=accountLifecycleService.delete(input);
        if(!Boolean.TRUE.equals(result.get("replayed"))) {
            String outcome;
            try { outcome=webSocketService.stopWebSocket(((Number)result.get("accountId")).longValue())?"STOPPED":"STOP_PENDING"; }
            catch(RuntimeException failure) { outcome="STOP_FAILED"; }
            accountLifecycleService.recordConnectionOutcome(result,outcome);
        }
        return ResultObject.success(result);
    }

    /**
     * 获取账号详情
     */
    @PostMapping("/detail")
    public ResultObject<GetAccountDetailRespDTO> getAccountDetail(@RequestBody GetAccountDetailReqDTO reqDTO) {
        try {
            Long id = reqDTO.getAccountId();
            XianyuAccount account = accountMapper.selectById(id);
            if (account == null) {
                return ResultObject.failed("账号不存在");
            }
            accountBrowserProfileService.decorate(account);
            GetAccountDetailRespDTO respDTO = new GetAccountDetailRespDTO();
            respDTO.setAccount(account);
            return ResultObject.success(respDTO);
        } catch (Exception e) {
            log.error("获取账号详情失败", e);
            return ResultObject.failed("获取账号详情失败: " + e.getMessage());
        }
    }



}
