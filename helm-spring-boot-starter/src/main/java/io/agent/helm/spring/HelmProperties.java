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

        /**
         * Optional path prefix for all mounted Helm routes (default none). Must start with {@code /}, must not contain
         * {@code *}, and trailing slashes are stripped.
         */
        private String routePrefix = "";

        /**
         * Dev-only opt-in to wire {@code SecurityContextExtractor.header()}, which trusts the client-set
         * {@code X-Helm-Principal} header. Never enable in production: any client can impersonate any principal.
         */
        private boolean devHeaderAuth = false;

        /**
         * Maximum request body size in bytes accepted by the Helm servlet. Defaults to 1 MiB; oversized bodies are
         * rejected with HTTP 413.
         */
        private int maxBodyBytes = 1024 * 1024;

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

        public boolean isDevHeaderAuth() {
            return devHeaderAuth;
        }

        public void setDevHeaderAuth(boolean devHeaderAuth) {
            this.devHeaderAuth = devHeaderAuth;
        }

        public int getMaxBodyBytes() {
            return maxBodyBytes;
        }

        public void setMaxBodyBytes(int maxBodyBytes) {
            this.maxBodyBytes = maxBodyBytes;
        }
    }
}
