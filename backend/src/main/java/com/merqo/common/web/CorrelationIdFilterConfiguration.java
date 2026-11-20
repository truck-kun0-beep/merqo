package com.merqo.common.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the {@link CorrelationIdFilter} with the servlet container.
 *
 * <p>Responsibility: make sure the filter runs for every request, before any controller, so that
 * even a failed request carries a correlation id.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Composition</b> - the filter and its registration are separate objects that work
 *       together; the filter itself knows nothing about the servlet container.
 *   <li><b>Open/closed principle</b> - the URL pattern and order can be changed here without
 *       touching the filter logic.
 * </ul>
 */
@Configuration
public class CorrelationIdFilterConfiguration {

    /**
     * The filter registration.
     *
     * @return the registration bean
     */
    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("correlationIdFilter");
        return registration;
    }
}
