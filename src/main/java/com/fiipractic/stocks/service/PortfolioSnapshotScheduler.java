package com.fiipractic.stocks.service;

import com.fiipractic.stocks.model.Portfolio;
import com.fiipractic.stocks.repository.PortfolioRepository;
import com.fiipractic.stocks.repository.PortfolioSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PortfolioSnapshotScheduler {
    private final PortfolioRepository portfolioRepository;
    private final PortfolioService portfolioService;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;

    private final static Logger log = LoggerFactory.getLogger(PortfolioSnapshotScheduler.class);

    public PortfolioSnapshotScheduler(
            PortfolioRepository portfolioRepository,
            PortfolioService portfolioService,
            PortfolioSnapshotRepository portfolioSnapshotRepository) {
        this.portfolioRepository = portfolioRepository;
        this.portfolioService = portfolioService;
        this.portfolioSnapshotRepository = portfolioSnapshotRepository;
    }

    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void captureAllSnapshots() {
        List<Portfolio> portfolios = portfolioRepository.findAll();

        log.info("Starting scheduled snapshot of {} portfolios", portfolios.size());

        for (var portfolio : portfolios) {
            portfolioService.calculateValuation(portfolio.getUserId(), portfolio.getId());
            try {
                MDC.put("action", "save_portfolio_snapshot");
                MDC.put("portfolioId", String.valueOf(portfolio.getId()));
                MDC.put("userId", portfolio.getUserId());

                log.info("Saved snapshot for portfolio with ID: {}", portfolio.getId());
            } finally {
                MDC.clear();
            }
        }

        log.info("Saved {} snapshots", portfolios.size());
    }
}
