package com.merqo.common.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the mode flags that decide which implementation Merqo uses.
 *
 * <p>Responsibility: read {@code DEMO_MODE}, {@code COURIER_MODE}, {@code PAYMENT_MODE}, {@code
 * WEBSITE_MODE} and {@code LLM_MODE} from the environment and present them as a small, typed value
 * object.
 *
 * <p>OOP concepts demonstrated:
 *
 * <ul>
 *   <li><b>Encapsulation</b> - raw strings never reach the rest of the code; only this class reads
 *       the environment.
 *   <li><b>Composition</b> - the five modes are grouped into one object instead of being passed
 *       around separately.
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "merqo")
public class MerqoModeProperties {

    private boolean demoMode = false;
    private String courierMode = "mock";
    private String paymentMode = "manual";
    private String websiteMode = "mock";
    private String llmMode = "mock";

    /**
     * Whether the demo experience is switched on.
     *
     * @return {@code true} in demo mode
     */
    public boolean isDemoMode() {
        return demoMode;
    }

    /**
     * Sets the demo flag.
     *
     * @param demoMode the new value
     */
    public void setDemoMode(boolean demoMode) {
        this.demoMode = demoMode;
    }

    /**
     * The courier adapter to use.
     *
     * @return the courier mode
     */
    public String getCourierMode() {
        return courierMode;
    }

    /**
     * Sets the courier mode.
     *
     * @param courierMode the new value
     */
    public void setCourierMode(String courierMode) {
        this.courierMode = courierMode;
    }

    /**
     * The payment verifier to use.
     *
     * @return the payment mode
     */
    public String getPaymentMode() {
        return paymentMode;
    }

    /**
     * Sets the payment mode.
     *
     * @param paymentMode the new value
     */
    public void setPaymentMode(String paymentMode) {
        this.paymentMode = paymentMode;
    }

    /**
     * The website order source to use.
     *
     * @return the website mode
     */
    public String getWebsiteMode() {
        return websiteMode;
    }

    /**
     * Sets the website mode.
     *
     * @param websiteMode the new value
     */
    public void setWebsiteMode(String websiteMode) {
        this.websiteMode = websiteMode;
    }

    /**
     * The language model implementation to use.
     *
     * @return the LLM mode
     */
    public String getLlmMode() {
        return llmMode;
    }

    /**
     * Sets the LLM mode.
     *
     * @param llmMode the new value
     */
    public void setLlmMode(String llmMode) {
        this.llmMode = llmMode;
    }

    /**
     * Every mode name in one list, used by the demo console and the health report.
     *
     * @return the active modes
     */
    public List<String> activeModes() {
        return List.of(
                "courier=" + courierMode,
                "payment=" + paymentMode,
                "website=" + websiteMode,
                "llm=" + llmMode);
    }
}
