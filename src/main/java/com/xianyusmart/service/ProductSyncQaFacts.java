package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.controller.dto.*;
import com.xianyusmart.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded external fixture facts only; all task and snapshot writes use production services. */
@Service
public class ProductSyncQaFacts {
 public enum Scenario { COMPLETE, EMPTY, SECOND_PAGE_FAILURE, REPEATED_PAGE, MALFORMED, DETAIL_FAILURE, SAVE_FAILURE, DELAY_LIST, DELAY_DETAIL, THREE_PAGES, UNPROVEN_EMPTY, EMPTY_RESPONSE, VERIFICATION, PERMISSION_DENIED, RATE_LIMITED }
 private record Fact(String key,Scenario scenario,long expires){}
 private final Environment environment;private final JdbcTemplate jdbc;private final boolean enabled;private final long tenant;
 private final Map<Long,Fact> facts=new ConcurrentHashMap<>();
 public ProductSyncQaFacts(Environment environment,JdbcTemplate jdbc,@Value("${app.product-sync-qa.enabled:false}") boolean enabled,@Value("${app.product-sync-qa.tenant-id:-1}") long tenant){this.environment=environment;this.jdbc=jdbc;this.enabled=enabled;this.tenant=tenant;if(enabled&&(!Arrays.asList(environment.getActiveProfiles()).contains("qa")||tenant<1))throw new IllegalStateException("Product sync QA requires explicit qa profile/tenant");}
 private void require(long account){if(!enabled||!Objects.equals(TenantContext.get(),tenant))throw new BusinessException(404,"同步QA未启用");if(jdbc.queryForObject("SELECT COUNT(*) FROM xianyu_account a WHERE a.id=? AND a.tenant_id=? AND a.unb LIKE 'QA-SYNC-%' AND NOT EXISTS(SELECT 1 FROM xianyu_cookie c WHERE c.xianyu_account_id=a.id)",Integer.class,account,tenant)!=1)throw new BusinessException(403,"仅允许无凭据QA-SYNC账号");}
 public void configure(long account,String key,Scenario scenario){require(account);if(key==null||!key.matches("QA-SYNC-[A-Za-z0-9._:-]{1,60}"))throw new BusinessException(400,"QA键无效");facts.entrySet().removeIf(e->e.getValue().expires<System.currentTimeMillis());if(facts.size()>=32&&!facts.containsKey(account))throw new BusinessException(409,"QA fixture数量已达上限");facts.put(account,new Fact(key,scenario,System.currentTimeMillis()+600000));}
 public boolean handles(long account){if(!enabled||!facts.containsKey(account))return false;fact(account);return true;}
 private Fact fact(long account){require(account);Fact f=facts.get(account);if(f==null||f.expires<System.currentTimeMillis())throw new BusinessException(409,"QA事实已过期");return f;}
 public ItemListRespDTO page(long account,int page,int size){Fact f=fact(account);if(f.scenario==Scenario.DELAY_LIST)delay();var data=new ItemListRespDTO();data.setPageNumber(page);data.setPageSize(size);data.setSavedCount(0);data.setItems(new ArrayList<>());data.setSuccess(true);data.setStructureValidated(true);
  if(Set.of(Scenario.EMPTY_RESPONSE,Scenario.VERIFICATION,Scenario.PERMISSION_DENIED,Scenario.RATE_LIMITED).contains(f.scenario)){data.setSuccess(false);data.setStructureValidated(false);data.setErrorCode(switch(f.scenario){case EMPTY_RESPONSE->"EMPTY_RESPONSE";case VERIFICATION->"VERIFICATION_REQUIRED";case PERMISSION_DENIED->"PLATFORM_PERMISSION_DENIED";default->"RATE_LIMITED";});return data;}
  if(f.scenario==Scenario.UNPROVEN_EMPTY){data.setCurrentCount(0);return data;}
  if(f.scenario==Scenario.THREE_PAGES){data.setReportedTotal(3L);var items=new ArrayList<ItemDTO>();for(int itemNumber:page==1?new int[]{1}:page==2?new int[]{1,2}:new int[]{3}){var item=new ItemDTO();String id=f.key+"-item-"+itemNumber;item.setId(id);item.setTitle("QA 在售商品 "+itemNumber);var params=new ItemDTO.DetailParams();params.setItemId(id);params.setTitle(item.getTitle());item.setDetailParams(params);item.setItemStatus(0);items.add(item);}data.setItems(items);data.setCurrentCount(items.size());data.setTerminalPage(page==3);data.setTerminationEvidence(page==3?"HAS_MORE_FALSE":"NOT_PROVIDED");return data;}
  if(f.scenario==Scenario.MALFORMED||f.scenario==Scenario.SECOND_PAGE_FAILURE&&page==2){data.setSuccess(false);data.setStructureValidated(false);data.setErrorCode("QA_LIST_UNAVAILABLE");return data;}
  int pages=f.scenario==Scenario.REPEATED_PAGE?100:2;if(f.scenario==Scenario.EMPTY||page>pages){data.setTerminalPage(true);data.setTerminationEvidence("HAS_MORE_FALSE");data.setCurrentCount(0);return data;}
  String itemId=f.key+"-item-"+(f.scenario==Scenario.REPEATED_PAGE?1:page);var item=new ItemDTO();item.setId(itemId);item.setTitle("QA 在售商品 "+page);var params=new ItemDTO.DetailParams();params.setItemId(itemId);params.setTitle(item.getTitle());item.setDetailParams(params);item.setItemStatus(0);data.setItems(List.of(item));data.setCurrentCount(1);return data;
 }
 public boolean saveFails(long account,String item){return fact(account).scenario==Scenario.SAVE_FAILURE;}
 public ItemDetailSyncService.SyncResult detail(long account,String item,ItemDetailSnapshotService snapshots){Fact f=fact(account);if(f.scenario==Scenario.DELAY_DETAIL)delay();if(f.scenario==Scenario.DETAIL_FAILURE)return new ItemDetailSyncService.SyncResult(ItemDetailSyncService.SyncStatus.UNAVAILABLE,"QA详情暂不可用","QA_FIXTURE");snapshots.save(account,item,"QA 可验证详情",List.of(),List.of());return new ItemDetailSyncService.SyncResult(ItemDetailSyncService.SyncStatus.SUCCESS,"QA详情已保存","QA_FIXTURE");}
 private void delay(){try{Thread.sleep(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new BusinessException(503,"QA读取中断");}}
}
