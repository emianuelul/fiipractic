package com.fiipractic.stocks.repository;

import com.fiipractic.stocks.model.PortfolioSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, Long> {
    Optional<List<PortfolioSnapshot>> findAllByPortfolioId(Long portfolioId);
}
