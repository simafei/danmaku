package com.lbank.danmaku.job.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "danmaku")
public class DanmakuProperties {

    private Telegram telegram = new Telegram();
    private Worker worker = new Worker();
    private Decision decision = new Decision();
    private Ai ai = new Ai();

    @Data
    public static class Telegram {
        private String botToken = "";
        private Polling polling = new Polling();

        @Data
        public static class Polling {
            private boolean enabled = true;
        }
    }

    @Data
    public static class Worker {
        private boolean enabled = true;
        private int batchSize = 50;
        private int maxRetry = 3;
        private int processingTimeoutMinutes = 5;

        public Duration processingTimeout() {
            return Duration.ofMinutes(processingTimeoutMinutes);
        }
    }

    @Data
    public static class Decision {
        private int minConfidence = 50;
        private long symbolRateLimitSeconds = 15;
        private long duplicateTtlSeconds = 60;

    }

    @Data
    public static class Ai {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String apiKey = "";
        private String model = "qwen-turbo";
        private Double temperature = 0.4;
        private String responseFormat = "json_object";
    }
}
