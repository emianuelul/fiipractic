package com.fiipractic.stocks.service;

import com.fiipractic.stocks.dto.*;
import com.fiipractic.stocks.exception.PortfolioNotFoundException;
import com.fiipractic.stocks.exception.UserNotOwnerOfPortfolioException;
import com.fiipractic.stocks.model.Portfolio;
import com.fiipractic.stocks.model.PortfolioHolding;
import com.fiipractic.stocks.model.PortfolioSnapshot;
import com.fiipractic.stocks.model.Stock;
import com.fiipractic.stocks.repository.PortfolioHoldingRepository;
import com.fiipractic.stocks.repository.PortfolioRepository;
import com.fiipractic.stocks.repository.PortfolioSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PortfolioService {

    private final PortfolioRepository portfolioRepository;
    private final PortfolioHoldingRepository portfolioHoldingRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final StockService stockService;
    private final PriceRefreshPublisher priceRefreshPublisher;

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    public PortfolioService(PortfolioRepository portfolioRepository,
                            PortfolioHoldingRepository portfolioHoldingRepository,
                            StockService stockService,
                            PriceRefreshPublisher priceRefreshPublisher,
                            PortfolioSnapshotRepository portfolioSnapshotRepository) {
        this.portfolioRepository = portfolioRepository;
        this.portfolioHoldingRepository = portfolioHoldingRepository;
        this.stockService = stockService;
        this.priceRefreshPublisher = priceRefreshPublisher;
        this.portfolioSnapshotRepository = portfolioSnapshotRepository;
    }

    @Transactional
    public PortfolioDTO createPortfolio(String userId, CreatePortfolioRequest request, String correlationId) {
        Portfolio portfolio = Portfolio.builder()
                .name(request.getName())
                .description(request.getDescription())
                .holdings(new ArrayList<>())
                .userId(userId)
                .build();

        portfolio = portfolioRepository.save(portfolio);

        try {
            MDC.put("action", "portfolio_created");
            MDC.put("userId", userId);
            MDC.put("portfolioId", String.valueOf(portfolio.getId()));
            MDC.put("portfolioName", portfolio.getName());
            MDC.put("correlationId", correlationId);
            log.info("Created portfolio with ID: {} and Name: {} for user with ID: {}", portfolio.getId(), portfolio.getName(), userId);
        } finally {
            MDC.clear();
        }

        return toDTO(portfolio);
    }

    @Transactional(readOnly = true)
    public List<PortfolioDTO> getUserPortfolios(String userId) {
        return portfolioRepository.findByUserId(userId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<PortfolioDTO> getAllPortfolios() {
        return portfolioRepository.findAll()
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public PortfolioDTO buyStock(String userId, Long portfolioId, BuyStockRequest request, String correlationId) {
        Portfolio portfolio = portfolioRepository.findById(portfolioId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new UserNotOwnerOfPortfolioException("User is not owner of portfolio or portfolio does not exist"));

        // find existing stock by symbol, or create it if it doesn't exist yet
        Stock stock = stockService.findOrCreate(request.getSymbol());

        PortfolioHolding holding = PortfolioHolding.builder()
                .portfolio(portfolio)
                .stock(stock)
                .quantity(request.getQuantity())
                .purchasePrice(request.getPurchasePrice())
                .build();

        portfolioHoldingRepository.save(holding);
        portfolio.getHoldings().add(holding);

        try {
            MDC.put("action", "bought_stock");
            MDC.put("portfolioId", String.valueOf(portfolioId));
            MDC.put("userId", userId);
            MDC.put("symbol", request.getSymbol());
            MDC.put("quantity", String.valueOf(request.getQuantity()));
            MDC.put("purchasePrice", request.getPurchasePrice().toString());
            MDC.put("correlationId", correlationId);
            log.info("Bought a stock (symbol: {}) for portfolio: {}", request.getSymbol(), portfolioId);
        } finally {
            MDC.clear();
        }

        return toDTO(portfolio);
    }

    public RefreshResponseDTO refreshPortfolioPrices(String userId, Long portfolioId, String correlationId) {
        Portfolio portfolio = portfolioRepository.findById(portfolioId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new UserNotOwnerOfPortfolioException("Portfolio not found or access denied"));

        // extract unique symbols from the portfolio's holdings
        List<String> symbols = portfolio.getHoldings().stream()
                .map(h -> h.getStock().getSymbol())
                .distinct()
                .toList();

        symbols.forEach(symbol -> {
            priceRefreshPublisher.publishRefresh(symbol, userId, correlationId);
        });

        return new RefreshResponseDTO(
                portfolioId.toString(),
                symbols,
                symbols.size(),
                "Price refresh queued for " + symbols.size() + " stocks"
        );
    }

    @Transactional
    public PortfolioSnapshotDTO calculateValuation(String userId, Long portfolioId) {
        Portfolio portfolio = portfolioRepository.findById(portfolioId).orElseThrow(() -> new PortfolioNotFoundException("Can't find portfolio with ID: " + portfolioId));
        if (!portfolio.getUserId().equals(userId)) {
            throw new UserNotOwnerOfPortfolioException("Portfolio with ID: " + portfolioId + " doesn't belong to user with ID: " + userId);
        }

        Map<String, List<PortfolioHolding>> holdingsBySymbol =
                portfolio.getHoldings().stream()
                        .collect(Collectors.groupingBy(h -> h.getStock().getSymbol()));

        List<PositionSummaryDTO> positions = new ArrayList<>();
        for (var entry : holdingsBySymbol.entrySet()) {
            String symbol = entry.getKey();

            List<PortfolioHolding> holdings = entry.getValue();

            Integer totalQuantity = holdings.stream()
                    .collect(Collectors.summingInt(PortfolioHolding::getQuantity));

            BigDecimal totalCost = holdings.stream()
                    .map(h -> h.getPurchasePrice().multiply(BigDecimal.valueOf(h.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal avgPrice = totalCost.divide(BigDecimal.valueOf(totalQuantity), 2, RoundingMode.HALF_UP);

            BigDecimal currentPrice = holdings.getFirst().getStock().getCurrentPrice();

            BigDecimal invested = avgPrice.multiply(BigDecimal.valueOf(totalQuantity));
            BigDecimal currentValue = currentPrice != null ? currentPrice.multiply(BigDecimal.valueOf(totalQuantity)) : BigDecimal.valueOf(0);
            BigDecimal profitLoss = currentValue.subtract(invested);
            BigDecimal plPercent = profitLoss.divide(invested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

            positions.add(new PositionSummaryDTO(symbol, totalQuantity, avgPrice, currentPrice, invested, currentValue, profitLoss, plPercent));
        }

        BigDecimal totalInvested = positions.stream().map(PositionSummaryDTO::invested).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCurrentValue = positions.stream().map(PositionSummaryDTO::currentValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalProfitLoss = totalCurrentValue.subtract(totalInvested);
        BigDecimal totalProfitLossPercent = totalProfitLoss.divide(totalInvested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

        PortfolioSnapshot portfolioSnapshot =
                PortfolioSnapshot.builder()
                        .portfolio(portfolio)
                        .currentValue(totalCurrentValue)
                        .totalInvested(totalInvested)
                        .profitLoss(totalProfitLoss)
                        .profitLossPercent(totalProfitLossPercent)
                        .build();

        portfolioSnapshotRepository.save(portfolioSnapshot);

        return new PortfolioSnapshotDTO(
                portfolioSnapshot.getId(),
                toDTO(portfolio),
                totalInvested,
                totalCurrentValue,
                totalProfitLoss,
                totalProfitLossPercent,
                portfolioSnapshot.getCreatedAt()
        );
    }

    private PortfolioDTO toDTO(Portfolio p) {
        PortfolioDTO dto = new PortfolioDTO();
        dto.setId(p.getId());
        dto.setName(p.getName());
        dto.setDescription(p.getDescription());
        dto.setCreatedAt(p.getCreatedAt());
        dto.setHoldings(p.getHoldings().stream().map(this::toHoldingDTO).collect(Collectors.toList()));
        return dto;
    }

    private HoldingDTO toHoldingDTO(PortfolioHolding h) {
        return new HoldingDTO(
                h.getId(),
                h.getStock().getSymbol(),
                h.getQuantity(),
                h.getPurchasePrice(),
                h.getPurchasedAt()
        );
    }
}