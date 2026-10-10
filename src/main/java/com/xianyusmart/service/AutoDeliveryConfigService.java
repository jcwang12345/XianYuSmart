package com.xianyusmart.service;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.AutoDeliveryConfigReqDTO;
import com.xianyusmart.controller.dto.AutoDeliveryConfigRespDTO;
import com.xianyusmart.controller.dto.AutoDeliveryConfigQueryReqDTO;
import com.xianyusmart.controller.dto.ReceiptFollowUpUpdateReqDTO;
import com.xianyusmart.controller.dto.UniformCardDeliveryReqDTO;

import java.util.List;

public interface AutoDeliveryConfigService {
    
    ResultObject<AutoDeliveryConfigRespDTO> saveOrUpdateConfig(AutoDeliveryConfigReqDTO reqDTO);

    ResultObject<Void> updateReceiptFollowUp(ReceiptFollowUpUpdateReqDTO reqDTO);

    ResultObject<AutoDeliveryConfigRespDTO> configureUniformCard(UniformCardDeliveryReqDTO reqDTO);
    
    ResultObject<AutoDeliveryConfigRespDTO> getConfig(AutoDeliveryConfigQueryReqDTO reqDTO);
    
    ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByGoodsId(Long xianyuAccountId, String xyGoodsId);
    
    ResultObject<List<AutoDeliveryConfigRespDTO>> getConfigsByAccountId(Long xianyuAccountId);
    
    ResultObject<Void> deleteConfig(Long xianyuAccountId, String xyGoodsId);
}
