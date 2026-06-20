package xyz.tcheeric.cashu.ledger.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import xyz.tcheeric.cashu.ledger.core.trace.QuoteStatusService;

/**
 * Periodically flips lapsed open quotes to terminal ({@code quote_expired}) via
 * {@link QuoteStatusService#sweepExpired(int)} (design §5.4.1 trigger 3 — the expiry half).
 * Wired only when ingest is enabled, since it maintains the activity cache the ingest path owns.
 */
public final class QuoteExpirySweeper {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuoteExpirySweeper.class);
    private static final int BATCH = 500;

    private final QuoteStatusService quoteStatusService;

    public QuoteExpirySweeper(QuoteStatusService quoteStatusService) {
        this.quoteStatusService = quoteStatusService;
    }

    @Scheduled(fixedDelayString = "${trace.ingest.quote-sweep-delay-ms:30000}")
    public void sweep() {
        int expired = quoteStatusService.sweepExpired(BATCH);
        if (expired > 0) {
            LOGGER.info("quote_expiry_sweep expired={}", expired);
        }
    }
}
