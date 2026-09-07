package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuKamiConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface XianyuKamiConfigMapper extends BaseMapper<XianyuKamiConfig> {

    @Select("SELECT * FROM xianyu_kami_config WHERE id = #{id} FOR UPDATE")
    XianyuKamiConfig lockById(@Param("id") Long id);

    @Select("SELECT DISTINCT config.* FROM xianyu_kami_config config " +
            "JOIN xianyu_kami_config_account link ON link.kami_config_id = config.id " +
            "WHERE link.xianyu_account_id = #{xianyuAccountId} ORDER BY config.create_time DESC")
    List<XianyuKamiConfig> findByAccountId(@Param("xianyuAccountId") Long xianyuAccountId);
}
