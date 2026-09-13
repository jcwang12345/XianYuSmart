package com.xianyusmart.service;

import com.xianyusmart.context.TenantContext;
import com.xianyusmart.entity.XianyuOperationLog;
import com.xianyusmart.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 店铺分组是账号、商品、订单、客服和通知共享的范围。 */
@Service
public class AccountGroupService {
    private final JdbcTemplate jdbcTemplate;
    private final AccountAccessService accountAccessService;
    private final OperationLogService operationLogService;

    public AccountGroupService(JdbcTemplate jdbcTemplate,
                               AccountAccessService accountAccessService,
                               OperationLogService operationLogService) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountAccessService = accountAccessService;
        this.operationLogService = operationLogService;
    }

    public List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("""
                SELECT groups.id,groups.group_name groupName,groups.color,groups.description,
                       groups.sort_order sortOrder,COUNT(member.id) accountCount,
                       GROUP_CONCAT(member.xianyu_account_id ORDER BY member.xianyu_account_id) accountIdsCsv,
                       groups.updated_time updatedTime
                  FROM xianyu_account_group groups
                  LEFT JOIN xianyu_account_group_member member ON member.tenant_id=groups.tenant_id AND member.group_id=groups.id
                 WHERE groups.tenant_id=? GROUP BY groups.id
                 ORDER BY groups.sort_order,groups.id
                """, tenant());
    }

    @Transactional
    public Map<String, Object> save(GroupCommand command) {
        if (command == null) throw new BusinessException(400, "分组参数不能为空");
        String requestId = required(command.requestId(), "requestId", 80);
        String name = required(command.groupName(), "分组名称", 100);
        String color = optional(command.color(), 16);
        String description = optional(command.description(), 500);
        int sortOrder = command.sortOrder() == null ? 0 : Math.max(0, command.sortOrder());
        if (command.id() == null) {
            jdbcTemplate.update("INSERT INTO xianyu_account_group (tenant_id,group_name,color,description,sort_order) VALUES (?,?,?,?,?)",
                    tenant(), name, color, description, sortOrder);
        } else {
            int updated = jdbcTemplate.update("UPDATE xianyu_account_group SET group_name=?,color=?,description=?,sort_order=? WHERE tenant_id=? AND id=?",
                    name, color, description, sortOrder, tenant(), command.id());
            if (updated == 0) throw new BusinessException(404, "分组不存在");
        }
        Long id = command.id() == null ? jdbcTemplate.queryForObject(
                "SELECT id FROM xianyu_account_group WHERE tenant_id=? AND group_name=?", Long.class, tenant(), name) : command.id();
        audit("ACCOUNT_GROUP_SAVE", "保存店铺分组", requestId, String.valueOf(id));
        return jdbcTemplate.queryForMap("SELECT id,group_name groupName,color,description,sort_order sortOrder FROM xianyu_account_group WHERE tenant_id=? AND id=?",
                tenant(), id);
    }

    @Transactional
    public void replaceMembers(Long groupId, MemberCommand command) {
        requireGroup(groupId);
        String requestId = required(command == null ? null : command.requestId(), "requestId", 80);
        List<Long> accounts = command.accountIds() == null ? List.of() : command.accountIds().stream()
                .filter(id -> id != null && id > 0).distinct().toList();
        accounts.forEach(accountId -> {
            accountAccessService.requireAccess(accountId);
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM xianyu_account WHERE tenant_id=? AND id=?",
                    Long.class, tenant(), accountId);
            if (count == null || count == 0) throw new BusinessException(400, "店铺不属于当前经营主体：" + accountId);
        });
        jdbcTemplate.update("DELETE FROM xianyu_account_group_member WHERE tenant_id=? AND group_id=?", tenant(), groupId);
        accounts.forEach(accountId -> jdbcTemplate.update(
                "INSERT INTO xianyu_account_group_member (tenant_id,group_id,xianyu_account_id) VALUES (?,?,?)",
                tenant(), groupId, accountId));
        audit("ACCOUNT_GROUP_MEMBERS", "更新店铺分组成员（" + accounts.size() + "个）", requestId, String.valueOf(groupId));
    }

    @Transactional
    public void delete(Long groupId, String requestId) {
        requireGroup(groupId);
        int accountCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM xianyu_account_group_member WHERE tenant_id=? AND group_id=?",
                Integer.class, tenant(), groupId);
        jdbcTemplate.update("DELETE FROM xianyu_account_group WHERE tenant_id=? AND id=?", tenant(), groupId);
        audit("ACCOUNT_GROUP_DELETE", "删除分组但保留" + accountCount + "个店铺", required(requestId, "requestId", 80), String.valueOf(groupId));
    }

    public List<Long> accountIds(Long groupId) {
        requireGroup(groupId);
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT xianyu_account_id FROM xianyu_account_group_member WHERE tenant_id=? AND group_id=? ORDER BY xianyu_account_id",
                Long.class, tenant(), groupId);
        ids.forEach(accountAccessService::requireAccess);
        return ids;
    }

    private void requireGroup(Long id) {
        if (id == null || id <= 0) throw new BusinessException(400, "分组ID无效");
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM xianyu_account_group WHERE tenant_id=? AND id=?",
                Long.class, tenant(), id);
        if (count == null || count == 0) throw new BusinessException(404, "分组不存在");
    }

    private void audit(String type, String description, String requestId, String targetId) {
        XianyuOperationLog log = new XianyuOperationLog();
        log.setOperationType(type);
        log.setOperationModule("店铺分组");
        log.setOperationDesc(description);
        log.setOperationStatus(1);
        log.setTargetType("ACCOUNT_GROUP");
        log.setTargetId(targetId);
        log.setRequestId(requestId);
        log.setIdempotencyKey(requestId);
        log.setOutcomeState("LOCAL_SUCCESS");
        log.setDataSource("LOCAL");
        operationLogService.log(log);
    }

    private Long tenant() { Long id=TenantContext.get(); if(id==null) throw new BusinessException(401,"缺少经营主体上下文"); return id; }
    private String required(String value,String label,int max){String text=optional(value,max);if(text==null)throw new BusinessException(400,label+"不能为空");return text;}
    private String optional(String value,int max){if(value==null||value.trim().isEmpty())return null;String text=value.trim();if(text.length()>max)throw new BusinessException(400,"内容不能超过"+max+"个字符");return text;}

    public record GroupCommand(Long id,String groupName,String color,String description,Integer sortOrder,String requestId) {}
    public record MemberCommand(List<Long> accountIds,String requestId) {}
}
