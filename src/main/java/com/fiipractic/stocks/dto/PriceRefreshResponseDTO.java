package com.fiipractic.stocks.dto;

public record PriceRefreshResponseDTO(
        String status,       // always "QUEUED"
        String symbol,       // the symbol that was queued (null for bulk refresh)
        String message       // human-readable description
) {
}