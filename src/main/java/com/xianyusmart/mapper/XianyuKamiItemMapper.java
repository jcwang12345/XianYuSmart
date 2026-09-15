package com.xianyusmart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xianyusmart.entity.XianyuKamiItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface XianyuKamiItemMapper extends BaseMapper<XianyuKamiItem> {

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = 0 ORDER BY sort_order ASC LIMIT 1")
    XianyuKamiItem findNextUnused(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = 0 ORDER BY RAND() LIMIT 1")
    XianyuKamiItem findRandomUnused(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} ORDER BY sort_order ASC")
    List<XianyuKamiItem> findByConfigId(@Param("kamiConfigId") Long kamiConfigId);

    @Select("<script>" +
            "SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} " +
            "<if test='status != null'>" +
            "AND status = #{status} " +
            "</if>" +
            "<if test='keyword != null and keyword != \"\"'>" +
            "AND kami_content LIKE CONCAT('%', #{keyword}, '%') " +
            "</if>" +
            "ORDER BY sort_order ASC" +
            "</script>")
    List<XianyuKamiItem> findByConfigIdWithFilter(
            @Param("kamiConfigId") Long kamiConfigId,
            @Param("status") Integer status,
            @Param("keyword") String keyword);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = 0")
    int countUnused(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = 1")
    int countUsed(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId}")
    int countByConfigId(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status IN (2, 3)")
    int countUnsettledByConfigId(@Param("kamiConfigId") Long kamiConfigId);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND kami_content = #{kamiContent}")
    int countByConfigIdAndContent(@Param("kamiConfigId") Long kamiConfigId, @Param("kamiContent") String kamiContent);

    @Update("UPDATE xianyu_kami_item SET status = 1, order_id = #{orderId}, used_time = NOW(3) WHERE id = #{id} AND status = 0")
    int markUsed(@Param("id") Long id, @Param("orderId") String orderId);

    @Update("UPDATE xianyu_kami_item SET status = 0, order_id = NULL, reserved_time = NULL, used_time = NULL WHERE id = #{id} AND status IN (1, 3)")
    int markUnused(@Param("id") Long id);

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = 0 " +
            "ORDER BY sort_order ASC, id ASC LIMIT #{quantity} FOR UPDATE")
    List<XianyuKamiItem> lockAvailable(@Param("kamiConfigId") Long kamiConfigId,
                                       @Param("quantity") int quantity);

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} " +
            "AND reserved_account_id = #{accountId} AND order_id = #{orderId} " +
            "AND status IN (1, 2, 3) ORDER BY sort_order ASC, id ASC FOR UPDATE")
    List<XianyuKamiItem> lockReservedByOrder(@Param("kamiConfigId") Long kamiConfigId,
                                             @Param("accountId") Long accountId,
                                             @Param("orderId") String orderId);

    @Select("SELECT * FROM xianyu_kami_item WHERE reserved_account_id = #{accountId} " +
            "AND order_id = #{orderId} AND status = #{status} ORDER BY id ASC")
    List<XianyuKamiItem> findByOrderAndStatus(@Param("accountId") Long accountId,
                                               @Param("orderId") String orderId,
                                               @Param("status") int status);

    @Select("SELECT COUNT(*) FROM xianyu_kami_item WHERE order_id = #{orderId} AND status = #{status}")
    int countByOrderAndStatus(@Param("orderId") String orderId, @Param("status") int status);

    @Update("<script>UPDATE xianyu_kami_item SET status = 2, order_id = #{orderId}, " +
            "reserved_account_id = #{accountId}, reservation_token = #{reservationToken}, " +
            "reservation_expire_time = DATE_ADD(NOW(3), INTERVAL 30 MINUTE), " +
            "source_config_version = #{configVersion}, reserved_time = NOW(3), row_version = row_version + 1 " +
            "WHERE status = 0 AND id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int reserve(@Param("ids") List<Long> ids, @Param("accountId") Long accountId,
                @Param("orderId") String orderId, @Param("reservationToken") String reservationToken,
                @Param("configVersion") Long configVersion);

    @Update("UPDATE xianyu_kami_item SET status = 1, used_time = NOW(3), " +
            "reservation_expire_time = NULL, row_version = row_version + 1 " +
            "WHERE reserved_account_id = #{accountId} AND order_id = #{orderId} AND status = 2")
    int commitReservation(@Param("accountId") Long accountId, @Param("orderId") String orderId);

    @Update("UPDATE xianyu_kami_item item JOIN xianyu_kami_config config ON config.id = item.kami_config_id " +
            "SET item.status = 0, item.order_id = NULL, item.reserved_account_id = NULL, " +
            "item.reservation_token = NULL, item.reservation_expire_time = NULL, " +
            "item.source_config_version = NULL, item.reserved_time = NULL, item.row_version = item.row_version + 1 " +
            "WHERE item.reserved_account_id = #{accountId} AND item.order_id = #{orderId} " +
            "AND item.status = 2 AND config.source_type = 'LOCAL'")
    int releaseReservation(@Param("accountId") Long accountId, @Param("orderId") String orderId);

    @Update("UPDATE xianyu_kami_item SET status = 3, reservation_expire_time = NULL, " +
            "row_version = row_version + 1 WHERE reserved_account_id = #{accountId} " +
            "AND order_id = #{orderId} AND status = 2")
    int markReservationReviewRequired(@Param("accountId") Long accountId, @Param("orderId") String orderId);

    @Select("SELECT * FROM xianyu_kami_item WHERE kami_config_id = #{kamiConfigId} AND status = #{status} ORDER BY sort_order ASC")
    List<XianyuKamiItem> findByConfigIdAndStatus(@Param("kamiConfigId") Long kamiConfigId, @Param("status") Integer status);
}
