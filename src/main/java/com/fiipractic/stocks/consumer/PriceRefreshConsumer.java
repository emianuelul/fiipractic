package com.fiipractic.stocks.consumer;

import com.fiipractic.stocks.config.RabbitMQConfig;
import com.fiipractic.stocks.dto.PriceRefreshMessage;
import com.fiipractic.stocks.exception.StockNotFoundException;
import com.fiipractic.stocks.model.Stock;
import com.fiipractic.stocks.repository.StockRepository;
import com.fiipractic.stocks.service.AlphaVantageClient;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
public class PriceRefreshConsumer {

    private static final Logger log = LoggerFactory.getLogger(PriceRefreshConsumer.class);

    private final AlphaVantageClient alphaVantageClient;
    private final StockRepository stockRepository;

    public PriceRefreshConsumer(AlphaVantageClient alphaVantageClient,
                                StockRepository stockRepository) {
        this.alphaVantageClient = alphaVantageClient;
        this.stockRepository = stockRepository;
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
        try {
            // 1.
            log.info("Symbol: {}, Requested By: {}, Current Thread Name: {}",
                    message.symbol(), message.requestedBy(), Thread.currentThread().getName());

            // 2.
            Thread.sleep(1000);

            // 3.
            BigDecimal price = alphaVantageClient.fetchLatestPrice(message.symbol());

            // 4.
            Stock stock = stockRepository.findBySymbol(message.symbol()).orElseThrow(() -> new StockNotFoundException("Can't find stock with symbol: " + message.symbol()));

            // 5.
            stock.setCurrentPrice(price);
            stock.setLastPriceUpdate(LocalDateTime.now());

            // 6.
            stockRepository.save(stock);

            // 7.
            log.info("Successfully Refreshed " + message.symbol() + " Stock " + "Price");

            // 8.
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("[CONSUMER] ❌ Failed to fetch price for [{}]: {}", message.symbol(), e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    @RabbitListener(queues = RabbitMQConfig.DLQ_NAME)
    public void onDeadLetter(PriceRefreshMessage message) {
        log.warn("[DLQ] Dead-lettered message — symbol: [{}], requestedBy: [{}], at: {}",
                message.symbol(), message.requestedBy(), message.requestedAt());
    }
}
