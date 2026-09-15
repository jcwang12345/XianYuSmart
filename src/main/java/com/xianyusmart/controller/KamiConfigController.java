package com.xianyusmart.controller;

import com.xianyusmart.common.ResultObject;
import com.xianyusmart.controller.dto.*;
import com.xianyusmart.service.KamiConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/kami-config")
public class KamiConfigController {

    @Autowired
    private KamiConfigService kamiConfigService;

    @PostMapping("/save")
    public ResultObject<KamiConfigRespDTO> saveOrUpdateConfig(@Valid @RequestBody KamiConfigReqDTO reqDTO) {
        try {
            log.info("保存卡密配置请求: id={}, xianyuAccountId={}", reqDTO.getId(), reqDTO.getXianyuAccountId());
            return kamiConfigService.createOrUpdateConfig(reqDTO);
        } catch (Exception e) {
            log.error("保存卡密配置失败", e);
            return ResultObject.failed("保存卡密配置失败: " + e.getMessage());
        }
    }

    @PostMapping("/list")
    public ResultObject<List<KamiConfigRespDTO>> getConfigsByAccountId(@RequestParam("xianyuAccountId") Long xianyuAccountId) {
        try {
            return kamiConfigService.getConfigsByAccountId(xianyuAccountId);
        } catch (Exception e) {
            log.error("查询卡密配置列表失败", e);
            return ResultObject.failed("查询卡密配置列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/detail")
    public ResultObject<KamiConfigRespDTO> getConfigById(@RequestParam("id") Long id) {
        try {
            return kamiConfigService.getConfigById(id);
        } catch (Exception e) {
            log.error("查询卡密配置详情失败", e);
            return ResultObject.failed("查询卡密配置详情失败: " + e.getMessage());
        }
    }

    @PostMapping("/delete")
    public ResultObject<Void> deleteConfig(@RequestParam("id") Long id,
                                           @RequestParam("requestId") String requestId) {
        try {
            return kamiConfigService.deleteConfig(id, requestId);
        } catch (Exception e) {
            log.error("删除卡密配置失败", e);
            return ResultObject.failed("删除卡密配置失败: " + e.getMessage());
        }
    }

    @PostMapping("/item/add")
    public ResultObject<KamiItemRespDTO> addKamiItem(@Valid @RequestBody KamiItemReqDTO reqDTO) {
        try {
            return kamiConfigService.addKamiItem(reqDTO);
        } catch (Exception e) {
            log.error("添加卡密失败", e);
            return ResultObject.failed("添加卡密失败: " + e.getMessage());
        }
    }

    @PostMapping("/item/batchImport")
    public ResultObject<Integer> batchImportKamiItems(@Valid @RequestBody KamiBatchImportReqDTO reqDTO) {
        try {
            return kamiConfigService.batchImportKamiItems(reqDTO);
        } catch (Exception e) {
            log.error("批量导入卡密失败", e);
            return ResultObject.failed("批量导入卡密失败: " + e.getMessage());
        }
    }

    @PostMapping("/item/list")
    public ResultObject<List<KamiItemRespDTO>> getKamiItemsByConfigId(@RequestParam("kamiConfigId") Long kamiConfigId) {
        try {
            return kamiConfigService.getKamiItemsByConfigId(kamiConfigId);
        } catch (Exception e) {
            log.error("查询卡密列表失败", e);
            return ResultObject.failed("查询卡密列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/item/query")
    public ResultObject<List<KamiItemRespDTO>> queryKamiItems(@RequestBody KamiItemQueryReqDTO reqDTO) {
        try {
            return kamiConfigService.getKamiItemsByConfigIdWithFilter(reqDTO);
        } catch (Exception e) {
            log.error("查询卡密列表失败", e);
            return ResultObject.failed("查询卡密列表失败: " + e.getMessage());
        }
    }

    @PostMapping("/item/delete")
    public ResultObject<Void> deleteKamiItem(@RequestParam("id") Long id,
                                             @RequestParam("requestId") String requestId) {
        try {
            return kamiConfigService.deleteKamiItem(id, requestId);
        } catch (Exception e) {
            log.error("删除卡密失败", e);
            return ResultObject.failed("删除卡密失败: " + e.getMessage());
        }
    }

    @PostMapping("/item/reset")
    public ResultObject<Void> resetKamiItem(@RequestParam("id") Long id,
                                            @RequestParam("requestId") String requestId) {
        try {
            return kamiConfigService.resetKamiItem(id, requestId);
        } catch (Exception e) {
            log.error("重置卡密状态失败", e);
            return ResultObject.failed("重置卡密状态失败: " + e.getMessage());
        }
    }

    @PostMapping("/item/export")
    public ResultObject<List<KamiItemRespDTO>> exportKamiItems(@RequestBody KamiExportReqDTO reqDTO) {
        try {
            return kamiConfigService.exportKamiItems(reqDTO);
        } catch (Exception e) {
            log.error("导出卡密失败", e);
            return ResultObject.failed("导出卡密失败: " + e.getMessage());
        }
    }

    @PostMapping("/external/circuit/reset")
    public ResultObject<KamiConfigRespDTO> resetExternalCircuit(@RequestBody CircuitResetRequest request) {
        return kamiConfigService.resetExternalCircuit(request.kamiConfigId(), request.requestId());
    }

    @GetMapping("/{kamiConfigId}/events")
    public ResultObject<Map<String, Object>> inventoryEvents(@PathVariable Long kamiConfigId,
                                                              @RequestParam(defaultValue = "1") Integer page,
                                                              @RequestParam(defaultValue = "20") Integer pageSize) {
        return kamiConfigService.getInventoryEvents(kamiConfigId, page, pageSize);
    }

    @GetMapping("/{kamiConfigId}/external/requests")
    public ResultObject<Map<String, Object>> externalRequests(@PathVariable Long kamiConfigId,
                                                               @RequestParam(required = false) String status,
                                                               @RequestParam(defaultValue = "1") Integer page,
                                                               @RequestParam(defaultValue = "20") Integer pageSize) {
        return kamiConfigService.getExternalRequests(kamiConfigId, status, page, pageSize);
    }

    @PostMapping("/external/requests/{externalRequestId}/resolution-preview")
    public ResultObject<Map<String, Object>> externalResolutionPreview(@PathVariable Long externalRequestId,
                                                                        @RequestBody ResolutionPreviewRequest request) {
        return kamiConfigService.previewExternalResolution(externalRequestId, request.decision());
    }

    @PostMapping("/external/requests/{externalRequestId}/resolve")
    public ResultObject<Map<String, Object>> resolveExternalRequest(@PathVariable Long externalRequestId,
                                                                     @RequestBody ExternalResolutionRequest request) {
        return kamiConfigService.resolveExternalRequest(externalRequestId, request.decision(),
                request.confirmationText(), request.cardContents(), request.note(), request.requestId());
    }

    public record CircuitResetRequest(Long kamiConfigId, String requestId) {
    }

    public record ResolutionPreviewRequest(String decision) {
    }

    public record ExternalResolutionRequest(String decision, String confirmationText,
                                            List<String> cardContents, String note, String requestId) {
    }
}
