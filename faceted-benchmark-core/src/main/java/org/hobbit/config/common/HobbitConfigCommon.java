package org.hobbit.config.common;

import org.springframework.context.annotation.Bean;

import com.google.gson.Gson;

public class HobbitConfigCommon {

	@Bean
	public Gson gson() {
		return new Gson();
	}
}
