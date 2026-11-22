package com.merqo.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * The session cookie of Merqo.
 *
 * <p>Responsibility: describe the one cookie that keeps the session. The cookie is:
 *
 * <ul>
 *   <li>{@code HttpOnly}, so a script cannot read it.
 *   <li>{@code Secure} in the prod profile, so it only travels over HTTPS.
 *   <li>{@code SameSite=Lax}, which is the right choice for a same origin single page application.
 *   <li><b>host only</b>, so no {@code Domain} attribute is written. The cookie never leaves the
 *       site of the frontend, even when the backend lives on another host.
 * </ul>
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Interface implementation</b> - the class implements Spring Session's {@link
 *       CookieSerializer}.
 *   <li><b>Strategy</b> - the cookie behaviour is one object that can be replaced in a test.
 * </ul>
 */
@Configuration
public class SessionCookieConfiguration {

    /**
     * Builds the cookie serializer.
     *
     * @param sessionProperties the session settings
     * @return the serializer
     */
    @Bean
    public CookieSerializer cookieSerializer(MerqoSessionProperties sessionProperties) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(sessionProperties.getCookieName());
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(sessionProperties.isSecureCookie());
        serializer.setSameSite("Lax");
        // No domain attribute is written, so the cookie stays host only.
        serializer.setUseBase64Encoding(true);
        return serializer;
    }
}
