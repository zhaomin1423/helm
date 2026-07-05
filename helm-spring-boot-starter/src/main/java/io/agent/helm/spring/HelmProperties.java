package io.agent.helm.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Helm Spring properties under the {@code helm.*} prefix. */
@ConfigurationProperties(prefix = "helm")
public class HelmProperties {
    private Http http = new Http();
    private int maxSessionMessages = 0;

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    /** Bounds session history; 0 keeps full history. */
    public int getMaxSessionMessages() {
        return maxSessionMessages;
    }

    public void setMaxSessionMessages(int maxSessionMessages) {
        this.maxSessionMessages = maxSessionMessages;
    }

    public static class Http {
        /** HTTP routes are disabled by default; set to true to mount the Helm servlet. */
        private boolean enabled = false;

        /** Optional path prefix for all mounted Helm routes (default none). */
        private String routePrefix = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getRoutePrefix() {
            return routePrefix;
        }

        public void setRoutePrefix(String routePrefix) {
            this.routePrefix = routePrefix;
        }
    }
}
