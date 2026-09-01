package com.syed.apiqa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class SyedApiQaApplication {

    static {
        // Allow setting Host header for anti-DNS rebinding and IP pinning
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host,connection");
    }

    public static void main(String[] args) {
        SpringApplication.run(SyedApiQaApplication.class, args);
    }
}
