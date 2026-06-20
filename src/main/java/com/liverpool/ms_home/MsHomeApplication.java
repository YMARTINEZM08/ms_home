package com.liverpool.ms_home;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MsHomeApplication {

	public static void main(String[] args) {
		SpringApplication.run(MsHomeApplication.class, args);
	}

}
