package com.template_update_service;

import org.springframework.boot.SpringApplication;

public class TestTemplateUpdateServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(TemplateUpdateServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
