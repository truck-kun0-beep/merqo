package com.merqo.orders.domain;

/**
 * Abstraction for parsing an incoming website order payload into the normalized {@link
 * IncomingOrder} domain shape.
 *
 * <p>Responsibility: decouple the order intake pipeline from wire payload formats so {@code
 * WEBSITE_MODE} can select between {@code GenericApiOrderSource}, {@code MockWebsiteOrderSource},
 * or future storefront adapters cleanly.
 *
 * <p>OOP concept: <b>Strategy / Adapter</b> — new ecommerce dialects implement this interface
 * without changing intake or confirmation services.
 */
public interface WebsiteOrderSource {

    /**
     * Identifier for the {@code WEBSITE_MODE} setting that activates this source (e.g. {@code
     * "generic"} or {@code "mock"}).
     *
     * @return mode key
     */
    String mode();

    /**
     * Parses a raw JSON payload into a normalized {@link IncomingOrder}.
     *
     * @param rawPayload raw request body
     * @return normalized incoming order
     * @throws OrderRuleException when the payload is structurally invalid
     */
    IncomingOrder parse(String rawPayload);
}
