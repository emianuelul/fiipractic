package com.fiipractic.stocks.model;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "portfolio_snapshots")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class PortfolioSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @Column(name = "total_invested")
    private BigDecimal totalInvested;

    @Column(name = "current_value")
    private BigDecimal currentValue;

    @Column(name = "profit_loss")
    private BigDecimal profitLoss;

    @Column(name = "profit_loss_percent")
    private BigDecimal profitLossPercent;

    @CreatedDate
    private LocalDateTime createdAt;

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PortfolioSnapshot that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
