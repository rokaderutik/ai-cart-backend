package com.rutikrokade.projects.aicartapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AicartappApplication {

	public static void main(String[] args) {
		var context = SpringApplication.run(AicartappApplication.class, args);
		if (context.getEnvironment().acceptsProfiles(org.springframework.core.env.Profiles.of("ingest", "knowledge-smoke", "retrieval-eval", "chat-smoke"))) {
			System.exit(SpringApplication.exit(context));
		}
	}

}
