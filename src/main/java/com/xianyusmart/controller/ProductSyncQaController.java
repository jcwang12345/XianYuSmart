package com.xianyusmart.controller;
import com.xianyusmart.common.ResultObject;
import com.xianyusmart.context.UserContext;
import com.xianyusmart.exception.BusinessException;
import com.xianyusmart.service.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/qa/sync")
public class ProductSyncQaController {
 private final ProductSyncQaFacts facts;private final AccountAccessService access;private final PlatformPermissionService permissions;
 public ProductSyncQaController(ProductSyncQaFacts facts,AccountAccessService access,PlatformPermissionService permissions){this.facts=facts;this.access=access;this.permissions=permissions;}
 public record Fixture(Long accountId,String key,ProductSyncQaFacts.Scenario scenario){}
 @PostMapping("/fixtures") public ResultObject<Map<String,Object>> fixture(@RequestBody Fixture input){
  if(input.accountId()==null||input.scenario()==null)throw new BusinessException(400,"QA输入缺失");access.requireAccess(input.accountId());if(!permissions.hasPermission(UserContext.getUserId(),PermissionCatalog.ACTION_GOODS_WRITE))throw new BusinessException(403,"无商品维护权限");facts.configure(input.accountId(),input.key(),input.scenario());return ResultObject.success(Map.of("accountId",input.accountId(),"key",input.key(),"scenario",input.scenario()));
 }
}
