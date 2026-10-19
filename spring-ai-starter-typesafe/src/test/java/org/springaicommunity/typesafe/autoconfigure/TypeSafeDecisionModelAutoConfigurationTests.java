/*
 * Copyright 2026 - 2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.typesafe.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeModels;
import org.springaicommunity.typesafe.decision.TypeSafeDecisionModel;

import org.springframework.ai.decision.DecisionModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TypeSafeDecisionModelAutoConfiguration}.
 *
 * @author Thomas Vitale
 */
class TypeSafeDecisionModelAutoConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(TypeSafeAutoConfiguration.class, TypeSafeDecisionModelAutoConfiguration.class));

	@Test
	void createsTheDecisionModelOnTopOfTheClient() {
		this.contextRunner
			.withPropertyValues("spring.ai.typesafe.api-key=test-api-key",
					"spring.ai.typesafe.model=" + TypeSafeModels.JEV_1_13_0)
			.run(context -> {
				assertThat(context).hasSingleBean(TypeSafeClient.class).hasSingleBean(DecisionModel.class);
				assertThat(context.getBean(TypeSafeDecisionModel.class).getOptions().getModel())
					.isEqualTo(TypeSafeModels.JEV_1_13_0);
			});
	}

	@Test
	void doesNothingWithoutAClient() {
		this.contextRunner.run(context -> assertThat(context).doesNotHaveBean(TypeSafeDecisionModel.class));
	}

	@Test
	void backsOffWhenAnotherDecisionModelIsSelected() {
		this.contextRunner
			.withPropertyValues("spring.ai.typesafe.api-key=test-api-key", "spring.ai.model.decision=openai")
			.run(context -> assertThat(context).hasSingleBean(TypeSafeClient.class)
				.doesNotHaveBean(TypeSafeDecisionModel.class));
		this.contextRunner
			.withPropertyValues("spring.ai.typesafe.api-key=test-api-key", "spring.ai.model.decision=typesafe")
			.run(context -> assertThat(context).hasSingleBean(TypeSafeDecisionModel.class));
	}

	@Test
	void backsOffWhenTheApplicationDefinesItsOwnDecisionModel() {
		this.contextRunner.withPropertyValues("spring.ai.typesafe.api-key=test-api-key")
			.withUserConfiguration(CustomDecisionModelConfiguration.class)
			.run(context -> assertThat(context).hasSingleBean(TypeSafeDecisionModel.class)
				.hasBean("customDecisionModel"));
	}

	@Test
	void doesNothingWithoutSpringAiOnTheClasspath() {
		this.contextRunner.withClassLoader(new FilteredClassLoader(DecisionModel.class))
			.withPropertyValues("spring.ai.typesafe.api-key=test-api-key")
			.run(context -> assertThat(context).hasSingleBean(TypeSafeClient.class)
				.doesNotHaveBean("typeSafeDecisionModel"));
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomDecisionModelConfiguration {

		@Bean
		TypeSafeDecisionModel customDecisionModel(TypeSafeClient typeSafeClient) {
			return TypeSafeDecisionModel.builder(typeSafeClient).build();
		}

	}

}
