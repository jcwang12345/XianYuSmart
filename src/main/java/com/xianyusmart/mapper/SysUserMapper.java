package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 系统用户Mapper
 * @date 2026/4/22
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT id FROM sys_user WHERE role = 'ADMIN' AND status = 1 FOR UPDATE")
    List<Long> lockActiveAdminIds();

    /** 只有恢复码集合仍等于读取快照时才消费，保证多实例并发下最多一个请求成功。 */
    @Update("UPDATE sys_user SET totp_recovery_codes = #{replacement} "
            + "WHERE id = #{userId} AND totp_recovery_codes = #{expected}")
    int consumeRecoveryCodesIfUnchanged(@Param("userId") Long userId,
                                        @Param("expected") String expected,
                                        @Param("replacement") String replacement);
}
