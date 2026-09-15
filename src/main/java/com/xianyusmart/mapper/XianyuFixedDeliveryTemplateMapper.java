package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuFixedDeliveryTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface XianyuFixedDeliveryTemplateMapper extends BaseMapper<XianyuFixedDeliveryTemplate> {

    @Select("SELECT DISTINCT template.* FROM xianyu_fixed_delivery_template template " +
            "JOIN xianyu_fixed_delivery_template_account link ON link.template_id = template.id " +
            "WHERE link.xianyu_account_id = #{accountId} ORDER BY template.update_time DESC")
    List<XianyuFixedDeliveryTemplate> findByAccountId(@Param("accountId") Long accountId);

    @Select("SELECT template.* FROM xianyu_fixed_delivery_template template " +
            "JOIN xianyu_fixed_delivery_template_account link ON link.template_id = template.id " +
            "WHERE template.id = #{id} AND link.xianyu_account_id = #{accountId} LIMIT 1")
    XianyuFixedDeliveryTemplate findOwnedById(@Param("accountId") Long accountId, @Param("id") Long id);

    @Select("SELECT * FROM xianyu_fixed_delivery_template WHERE xianyu_account_id = #{accountId} AND template_name = #{name} LIMIT 1")
    XianyuFixedDeliveryTemplate findByAccountIdAndName(@Param("accountId") Long accountId,
                                                       @Param("name") String name);

    @Select("SELECT COUNT(*) FROM xianyu_goods_auto_delivery_config WHERE fixed_template_id = #{templateId}")
    int countReferencedConfigs(@Param("templateId") Long templateId);

    @Select("SELECT * FROM xianyu_fixed_delivery_template WHERE id = #{id} FOR UPDATE")
    XianyuFixedDeliveryTemplate lockById(@Param("id") Long id);

    @Select("SELECT config.id AS configId,config.xianyu_account_id AS accountId," +
            "config.xy_goods_id AS goodsId,config.sku_id AS skuId,config.sku_name AS skuName " +
            "FROM xianyu_goods_auto_delivery_config config " +
            "WHERE config.fixed_template_id = #{templateId} ORDER BY config.id DESC")
    List<Map<String, Object>> findReferences(@Param("templateId") Long templateId);
}
