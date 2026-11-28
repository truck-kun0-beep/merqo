package com.merqo.orders;

import java.util.List;

import org.springframework.stereotype.Component;

import com.merqo.common.config.MerqoModeProperties;
import com.merqo.orders.domain.WebsiteOrderSource;

/**
 * Selects the active {@link WebsiteOrderSource} matching {@code WEBSITE_MODE}, falling back to
 * {@link GenericApiOrderSource} when a mock adapter is not active or separate webhook dialects
 * share the standard JSON contract.
 */
@Component
public class WebsiteOrderSourceResolver {

    private final List<WebsiteOrderSource> sources;
    private final GenericApiOrderSource defaultSource;
    private final MerqoModeProperties modeProperties;

    public WebsiteOrderSourceResolver(
            List<WebsiteOrderSource> sources,
            GenericApiOrderSource defaultSource,
            MerqoModeProperties modeProperties) {
        this.sources = sources;
        this.defaultSource = defaultSource;
        this.modeProperties = modeProperties;
    }

    public WebsiteOrderSource activeSource() {
        String configured = modeProperties.getWebsiteMode();
        if (configured != null) {
            for (WebsiteOrderSource source : sources) {
                if (configured.equalsIgnoreCase(source.mode())) {
                    return source;
                }
            }
        }
        return defaultSource;
    }
}
