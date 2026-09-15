package com.xianyusmart.service;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.*;
import com.xianyusmart.entity.XianyuKamiConfig;
import com.xianyusmart.entity.XianyuKamiItem;

import java.util.List;
import java.util.Map;

public interface KamiConfigService {

    ResultObject<KamiConfigRespDTO> createOrUpdateConfig(KamiConfigReqDTO reqDTO);

    ResultObject<List<KamiConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId);

    ResultObject<KamiConfigRespDTO> getConfigById(Long id);

    ResultObject<Void> deleteConfig(Long id, String requestId);

    ResultObject<KamiItemRespDTO> addKamiItem(KamiItemReqDTO reqDTO);

    ResultObject<Integer> batchImportKamiItems(KamiBatchImportReqDTO reqDTO);

    ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigId(Long kamiConfigId);

    ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigIdWithFilter(KamiItemQueryReqDTO reqDTO);

    ResultObject<Void> deleteKamiItem(Long id, String requestId);

    ResultObject<Void> resetKamiItem(Long id, String requestId);

    XianyuKamiItem acquireKami(Long kamiConfigId, String orderId);

    List<XianyuKamiItem> reserveKami(Long kamiConfigId, String orderId, Long accountId, int quantity);

    void commitReservation(String orderId, Long accountId, String xyGoodsId, String buyerUserId, String buyerUserName);

    void releaseReservation(String orderId, Long accountId);

    void markReservationReviewRequired(String orderId, Long accountId);

    XianyuKamiConfig getConfig(Long kamiConfigId);

    ResultObject<List<KamiItemRespDTO>> exportKamiItems(KamiExportReqDTO reqDTO);

    ResultObject<KamiConfigRespDTO> resetExternalCircuit(Long kamiConfigId, String requestId);

    ResultObject<Map<String, Object>> getInventoryEvents(Long kamiConfigId, Integer page, Integer pageSize);

    ResultObject<Map<String, Object>> getExternalRequests(Long kamiConfigId, String status,
                                                           Integer page, Integer pageSize);

    ResultObject<Map<String, Object>> previewExternalResolution(Long externalRequestId, String decision);

    ResultObject<Map<String, Object>> resolveExternalRequest(Long externalRequestId, String decision,
                                                             String confirmationText,
                                                             List<String> cardContents, String note,
                                                             String requestId);
}
