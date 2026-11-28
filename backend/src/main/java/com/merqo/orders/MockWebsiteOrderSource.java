package com.merqo.orders;

import org.springframework.stereotype.Component;

import com.merqo.orders.domain.IncomingOrder;
import com.merqo.orders.domain.WebsiteOrderSource;

/**
 * Built-in mock website adapter activated when {@code WEBSITE_MODE=mock}. Uses the same canonical
 * webhook contract so local simulators and Demo Console submissions follow the real intake and
 * signature pipeline end-to-end.
 */
@Component
public class MockWebsiteOrderSource implements WebsiteOrderSource {

    private final GenericApiOrderSource genericDelegate;

    public MockWebsiteOrderSource(GenericApiOrderSource genericDelegate) {
        this.genericDelegate = genericDelegate;
    }

    @Override
    public String mode() {
        return "mock";
    }

    @Override
    public IncomingOrder parse(String rawPayload) {
        return genericDelegate.parse(rawPayload);
    }
}
