package com.insurance.biz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.insurance.biz.mapper")
public class InsuranceBizApplication {
    public static void main(String[] args) {
        SpringApplication.run(InsuranceBizApplication.class, args);
    }
}
