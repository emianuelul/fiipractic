package com.fiipractic.stocks.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class PortfolioSnapshotDTO {
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PortfolioSnapshotDTO that)) return false;
        return Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }

    private Long id;
    private PortfolioDTO portfolio;
    private BigDecimal totalInvested;
    private BigDecimal currentValue;
    private BigDecimal profitLoss;
    private BigDecimal profitLossPercent;
    private LocalDateTime createdAt;
}
