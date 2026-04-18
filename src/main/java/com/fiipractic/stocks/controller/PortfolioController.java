package com.fiipractic.stocks.controller;

import com.fiipractic.stocks.dto.*;
import com.fiipractic.stocks.service.PortfolioService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/portfolios")
public class PortfolioController {

    private final PortfolioService portfolioService;
    private static final Logger log = LoggerFactory.getLogger(PortfolioController.class);

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @PostMapping
    public ResponseEntity<PortfolioDTO> createPortfolio(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePortfolioRequest request) {
        String correlationId = UUID.randomUUID().toString();

        try {
            MDC.put("action", "create_portfolio_request");
            MDC.put("name", request.getName());
            MDC.put("userId", jwt.getSubject());
            MDC.put("correlationId", correlationId);
            log.info("Requested portfolio creation for user with ID: {}", jwt.getSubject());
        } finally {
            MDC.clear();
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(portfolioService.createPortfolio(jwt.getSubject(), request, correlationId));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('USER', 'PREMIUM', 'ADMIN')")
    public ResponseEntity<List<PortfolioDTO>> getMyPortfolios(
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(portfolioService.getUserPortfolios(jwt.getSubject()));
    }

    @PostMapping("/{portfolioId}/stocks")
    public ResponseEntity<PortfolioDTO> buyStock(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long portfolioId,
            @Valid @RequestBody BuyStockRequest request) {
        String correlationId = UUID.randomUUID().toString();

        try {
            MDC.put("action", "buy_stock_requested");
            MDC.put("portfolioId", String.valueOf(portfolioId));
            MDC.put("userId", jwt.getSubject());
            MDC.put("symbol", request.getSymbol());
            MDC.put("correlationId", correlationId);
            log.info("Requested stock (symbol: {}) acquisition for portfolio ID: {}", request.getSymbol(), portfolioId);
        } finally {
            MDC.clear();
        }

        return ResponseEntity.ok(portfolioService.buyStock(jwt.getSubject(), portfolioId, request, correlationId));
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PortfolioDTO>> getAllPortfolios() {
        return ResponseEntity.ok(portfolioService.getAllPortfolios());
    }

    @PostMapping("/{portfolioId}/refresh")
    public ResponseEntity<RefreshResponseDTO> refreshPortfolioPrices(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long portfolioId) {
        String correlationId = UUID.randomUUID().toString();

        try {
            MDC.put("action", "portfolio_refresh_requested");
            MDC.put("portfolioId", String.valueOf(portfolioId));
            MDC.put("userId", jwt.getSubject());
            MDC.put("correlationId", correlationId);
            log.info("Requested portfolio refresh for portfolio with ID: {}", portfolioId);
        } finally {
            MDC.clear();
        }

        return ResponseEntity.ok(
                portfolioService.refreshPortfolioPrices(jwt.getSubject(), portfolioId, correlationId)
        );
    }

    @GetMapping("/{portfolioId}/valuation")
    public ResponseEntity<PortfolioSnapshotDTO> getPortfolioValuation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long portfolioId) {
        return ResponseEntity.ok(
                portfolioService.calculateValuation(jwt.getSubject(), portfolioId)
        );
    }

    @GetMapping("/{portfolioId}/valuationHistory")
    public ResponseEntity<List<PortfolioSnapshotDTO>> getPortfolioValuationHistory(@AuthenticationPrincipal Jwt jwt,
                                                                                   @PathVariable Long portfolioId) {
        return ResponseEntity.ok(
                portfolioService.getPortfolioValuationHistory(jwt.getSubject(), portfolioId));

    }

}
