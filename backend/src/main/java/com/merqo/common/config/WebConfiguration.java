package com.merqo.common.config;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

/**
 * Web layer configuration shared by every module.
 *
 * <p>Responsibility: keep the API prefix consistent and make the language fixed to English, as
 * required by the project brief.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - this class implements {@link WebMvcConfigurer}, which is
 *       the framework's extension point for MVC settings.
 *   <li><b>Composition</b> - the locale resolver and the interceptor work together to keep the
 *       language stable.
 * </ul>
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    /**
     * The API prefix used by every endpoint.
     *
     * @return the API prefix
     */
    public static String apiPrefix() {
        return "/api/v1";
    }

    /**
     * Pins the request locale to English.
     *
     * @return the locale resolver
     */
    @Bean
    public LocaleResolver localeResolver() {
        org.springframework.web.servlet.i18n.FixedLocaleResolver fixedLocaleResolver =
                new org.springframework.web.servlet.i18n.FixedLocaleResolver(Locale.ENGLISH);
        fixedLocaleResolver.setDefaultLocale(Locale.ENGLISH);
        return fixedLocaleResolver;
    }

    /**
     * Allows the language to be chosen with a request parameter but never leaves English as the
     * fallback.
     *
     * @return the locale change interceptor
     */
    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        interceptor.setIgnoreInvalidLocale(true);
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }

    /**
     * Makes placeholders such as {@code ${DEMO_MODE}} resolvable from the environment in every
     * configuration file.
     *
     * @return the placeholder configurer
     */
    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }
}
