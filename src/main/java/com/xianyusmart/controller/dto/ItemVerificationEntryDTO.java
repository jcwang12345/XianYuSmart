package com.xianyusmart.controller.dto;

/** Metadata is safe to display. The consumed URL is deliberately absent from toString. */
public final class ItemVerificationEntryDTO {
    private ItemVerificationEntryDTO() { }
    public record Target(Long xianyuAccountId, String xyGoodsId, String entryHandle) { }
    public record Metadata(boolean available, String entryHandle, Long expiresAt,
                           String officialHost, String safeStaticPath, String reason) { }
    public record Consumed(String url) {
        @Override public String toString() { return "Consumed[url=OMITTED]"; }
    }
}
