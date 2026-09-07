package com.xianyusmart.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 多账号共享资源的关联表访问。 */
@Mapper
public interface SharedAccountLinkMapper {

    @Select("SELECT xianyu_account_id FROM merchant_resource_account WHERE resource_id = #{id} ORDER BY xianyu_account_id")
    List<Long> selectResourceAccounts(@Param("id") Long id);

    @Delete("DELETE FROM merchant_resource_account WHERE resource_id = #{id}")
    int deleteResourceAccounts(@Param("id") Long id);

    @Insert("<script>INSERT INTO merchant_resource_account (resource_id, tenant_id, xianyu_account_id) VALUES " +
            "<foreach collection='accountIds' item='accountId' separator=','>(#{id}, #{tenantId}, #{accountId})</foreach></script>")
    int insertResourceAccounts(@Param("id") Long id, @Param("tenantId") Long tenantId,
                               @Param("accountIds") List<Long> accountIds);

    @Select("SELECT xianyu_account_id FROM xianyu_keyword_reply_rule_account WHERE rule_id = #{id} ORDER BY xianyu_account_id")
    List<Long> selectKeywordRuleAccounts(@Param("id") Long id);

    @Delete("DELETE FROM xianyu_keyword_reply_rule_account WHERE rule_id = #{id}")
    int deleteKeywordRuleAccounts(@Param("id") Long id);

    @Insert("<script>INSERT INTO xianyu_keyword_reply_rule_account (rule_id, tenant_id, xianyu_account_id) VALUES " +
            "<foreach collection='accountIds' item='accountId' separator=','>(#{id}, #{tenantId}, #{accountId})</foreach></script>")
    int insertKeywordRuleAccounts(@Param("id") Long id, @Param("tenantId") Long tenantId,
                                  @Param("accountIds") List<Long> accountIds);

    @Select("SELECT xianyu_account_id FROM xianyu_fixed_delivery_template_account WHERE template_id = #{id} ORDER BY xianyu_account_id")
    List<Long> selectFixedTemplateAccounts(@Param("id") Long id);

    @Delete("DELETE FROM xianyu_fixed_delivery_template_account WHERE template_id = #{id}")
    int deleteFixedTemplateAccounts(@Param("id") Long id);

    @Insert("<script>INSERT INTO xianyu_fixed_delivery_template_account (template_id, tenant_id, xianyu_account_id) VALUES " +
            "<foreach collection='accountIds' item='accountId' separator=','>(#{id}, #{tenantId}, #{accountId})</foreach></script>")
    int insertFixedTemplateAccounts(@Param("id") Long id, @Param("tenantId") Long tenantId,
                                    @Param("accountIds") List<Long> accountIds);

    @Select("SELECT xianyu_account_id FROM xianyu_kami_config_account WHERE kami_config_id = #{id} ORDER BY xianyu_account_id")
    List<Long> selectKamiConfigAccounts(@Param("id") Long id);

    @Delete("DELETE FROM xianyu_kami_config_account WHERE kami_config_id = #{id}")
    int deleteKamiConfigAccounts(@Param("id") Long id);

    @Insert("<script>INSERT INTO xianyu_kami_config_account (kami_config_id, tenant_id, xianyu_account_id) VALUES " +
            "<foreach collection='accountIds' item='accountId' separator=','>(#{id}, #{tenantId}, #{accountId})</foreach></script>")
    int insertKamiConfigAccounts(@Param("id") Long id, @Param("tenantId") Long tenantId,
                                 @Param("accountIds") List<Long> accountIds);
}
