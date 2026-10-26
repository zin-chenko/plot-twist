package io.plottwist.domain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class DomainApplication {
    public static void main(String[] args) {
        SpringApplication.run(DomainApplication.class, args);
    }
}
