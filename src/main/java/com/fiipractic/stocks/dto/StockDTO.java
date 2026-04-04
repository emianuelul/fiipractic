package com.fiipractic.stocks.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockDTO(Long id, String symbol, BigDecimal currentPrice, LocalDateTime lastPriceUpdate) {
}