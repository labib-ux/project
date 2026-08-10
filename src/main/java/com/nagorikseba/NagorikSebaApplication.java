package com.nagorikseba;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NagorikSebaApplication {

	public static void main(String[] args) {
		SpringApplication.run(NagorikSebaApplication.class, args);
	}

}
