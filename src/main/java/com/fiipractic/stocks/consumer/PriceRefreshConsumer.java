package com.fiipractic.stocks.consumer;

import com.fiipractic.stocks.config.RabbitMQConfig;
import com.fiipractic.stocks.dto.PriceRefreshMessage;
import com.fiipractic.stocks.exception.InvalidSymbolException;
import com.fiipractic.stocks.exception.StockNotFoundException;
import com.fiipractic.stocks.model.Stock;
import com.fiipractic.stocks.repository.StockRepository;
import com.fiipractic.stocks.service.AlphaVantageClient;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.slf4j.MDC;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
public class PriceRefreshConsumer {

    private static final Logger log = LoggerFactory.getLogger(PriceRefreshConsumer.class);

    private final AlphaVantageClient alphaVantageClient;
    private final StockRepository stockRepository;

    private final int cacheLifetimeMinutes;
    private static final int SLOW_API_THRESHOLD_MS = 3000;

    public PriceRefreshConsumer(AlphaVantageClient alphaVantageClient,
                                StockRepository stockRepository,
                                @Value("${values.price-cache-lifetime-minutes}") int cacheLifetimeMinutes) {
        this.alphaVantageClient = alphaVantageClient;
        this.stockRepository = stockRepository;
        this.cacheLifetimeMinutes = cacheLifetimeMinutes;
    }

    @RabbitListener(
            queues = RabbitMQConfig.PRICE_REFRESH_QUEUE,
            concurrency = "1",   // < critical: only 1 thread → 1 API call at a time
            ackMode = "MANUAL"   // < we control when the message is considered "done"
    )
    public void onPriceRefreshRequest(
            PriceRefreshMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException, InterruptedException {
        String correlationId = message.correlationId();
        log.info("[CONSUMER] Processing [{}] requested by [{}] - correlationId: {} - thread: {}",
                message.symbol(), message.requestedBy(), correlationId,
                Thread.currentThread().getName());

        try {
            MDC.put("action", "price_processing_started");
            MDC.put("symbol", message.symbol());
            MDC.put("requestedBy", message.requestedBy());
            MDC.put("correlationId", correlationId);
            log.info("Started processing price refresh for {}", message.symbol());
        } finally {
            MDC.clear();
        }

        long startTime = System.currentTimeMillis();

        Stock stock = stockRepository.findBySymbol(message.symbol()).orElseThrow(() -> new StockNotFoundException("Can't find stock with symbol: " + message.symbol()));

        try {
            log.info("Symbol: {}, Requested By: {}, Current Thread Name: {}",
                    message.symbol(), message.requestedBy(), Thread.currentThread().getName());

            Thread.sleep(1000);

            BigDecimal price;

            if (stock.isValid()) {
                boolean expired =
                        stock.getLastPriceUpdate() == null
                                || stock.getLastPriceUpdate().isBefore(LocalDateTime.now().minusMinutes(cacheLifetimeMinutes));

                if (expired) {
                    price = alphaVantageClient.fetchLatestPrice(message.symbol());
                } else {
                    price = stock.getCurrentPrice();
                    try {
                        MDC.put("action", "price_cache_hit");
                        MDC.put("symbol", message.symbol());
                        MDC.put("correlationId", correlationId);
                        log.info("Price for {} is recent, skipping Alpha Vantage call", message.symbol());
                    } finally {
                        MDC.clear();
                    }
                }

                stock.setCurrentPrice(price);
                stock.setLastPriceUpdate(LocalDateTime.now());

                stockRepository.save(stock);

                log.info("Successfully Refreshed " + message.symbol() + " Stock " + "Price");

                long durationMs = System.currentTimeMillis() - startTime;
                channel.basicAck(deliveryTag, false);

                try {
                    MDC.put("action", "price_stored");
                    MDC.put("symbol", message.symbol());
                    MDC.put("price", price.toString());
                    MDC.put("durationMs", String.valueOf(durationMs));
                    MDC.put("requestedBy", message.requestedBy());
                    MDC.put("correlationId", correlationId);
                    log.info("Price updated for {}: ${}", message.symbol(), price);
                } finally {
                    MDC.clear();
                }

                if (durationMs > SLOW_API_THRESHOLD_MS) {
                    try {
                        MDC.put("action", "slow_api_call");
                        MDC.put("symbol", message.symbol());
                        MDC.put("durationMs", String.valueOf(durationMs));
                        MDC.put("thresholdMs", String.valueOf(SLOW_API_THRESHOLD_MS));
                        MDC.put("correlationId", correlationId);
                        log.warn("Slow API call for {} took {}ms", message.symbol(), durationMs);
                    } finally {
                        MDC.clear();
                    }
                }
            } else {
                log.info("Symbol {} is already marked as valid, acknowledging without processing", message.symbol());
                channel.basicAck(deliveryTag, false);
                return;
            }

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;

            if (stock != null && stock.isValid()) {
                stock.setValid(false);
                stock.setCurrentPrice(BigDecimal.valueOf(0));
                stockRepository.save(stock);

                try {
                    MDC.put("action", "mark_symbol_invalid");
                    MDC.put("symbol", message.symbol());
                    MDC.put("reason", e.getMessage());
                    log.warn("Symbol {} has been marked as INVALID and will be skipped in the future", message.symbol());
                } finally {
                    MDC.clear();
                }
            }

            // Log error with MDC
            try {
                MDC.put("action", "price_fetch_failed");
                MDC.put("symbol", message.symbol());
                MDC.put("error", e.getMessage());
                MDC.put("errorType", e.getClass().getSimpleName());
                MDC.put("durationMs", String.valueOf(durationMs));
                MDC.put("requestedBy", message.requestedBy());
                MDC.put("correlationId", correlationId);
                log.error("Failed to fetch price for {}", message.symbol(), e);
            } finally {
                MDC.clear();
            }

            channel.basicNack(deliveryTag, false, false);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.DLQ_NAME)
    public void onDeadLetter(PriceRefreshMessage message) {
        log.warn("[DLQ] Dead-lettered message — symbol: [{}], requestedBy: [{}], at: {}",
                message.symbol(), message.requestedBy(), message.requestedAt());
    }
}
