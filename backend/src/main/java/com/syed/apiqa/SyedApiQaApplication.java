package com.syed.apiqa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class SyedApiQaApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyedApiQaApplication.class, args);
    }
}
