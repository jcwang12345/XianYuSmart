package com.xianyusmart.service;

import com.xianyusmart.controller.dto.SyncProgressRespDTO;
import com.xianyusmart.controller.dto.ItemDTO;
import java.util.List;

public interface ItemDetailSyncService {
    String startSync(Long accountId, List<ItemDTO> items);
    SyncProgressRespDTO getProgress(String syncId);
    void cancelSync(String syncId);
    boolean isSyncing(Long accountId);
    boolean syncSingleItem(Long accountId, String itemId);
    SyncResult syncSingleItemWithResult(Long accountId, String itemId);

    enum SyncStatus {
        SUCCESS,
        VERIFICATION_REQUIRED,
        BUSY,
        FORBIDDEN,
        NOT_FOUND,
        UNAVAILABLE
    }

    record SyncResult(SyncStatus status, String message, String source) {
        public boolean isSuccess() {
            return status == SyncStatus.SUCCESS;
        }

        public int businessCode() {
            return switch (status) {
                case SUCCESS -> 200;
                case FORBIDDEN -> 403;
                case NOT_FOUND -> 404;
                case VERIFICATION_REQUIRED, BUSY -> 409;
                case UNAVAILABLE -> 503;
            };
        }
    }
}
