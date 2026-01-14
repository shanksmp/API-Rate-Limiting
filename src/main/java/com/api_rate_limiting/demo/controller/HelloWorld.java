package com.api_rate_limiting.demo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloWorld {

    @GetMapping("/api/hello")
    public String Hello(){
        return "hello";
    }

    @GetMapping("/hello")
        public String HelloPart2(){
            return "hello";
        }
    }

