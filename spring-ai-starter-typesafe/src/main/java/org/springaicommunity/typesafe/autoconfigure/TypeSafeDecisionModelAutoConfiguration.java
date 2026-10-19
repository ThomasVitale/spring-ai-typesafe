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

import io.micrometer.observation.ObservationRegistry;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.decision.TypeSafeDecisionModel;

import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.observation.DecisionModelObservationConvention;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures a {@link TypeSafeDecisionModel} on top of the auto-configured
 * {@link TypeSafeClient}, when {@code typesafe-spring-ai} is on the classpath and
 * {@code spring.ai.model.decision} is {@code typesafe} or unset.
 *
 * @author Thomas Vitale
 */
@AutoConfiguration(after = TypeSafeAutoConfiguration.class)
@ConditionalOnClass({ TypeSafeDecisionModel.class, DecisionModel.class })
@ConditionalOnBean(TypeSafeClient.class)
@ConditionalOnProperty(name = SpringAIModelProperties.DECISION_MODEL, havingValue = TypeSafeDecisionModel.PROVIDER,
		matchIfMissing = true)
public class TypeSafeDecisionModelAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public TypeSafeDecisionModel typeSafeDecisionModel(TypeSafeClient typeSafeClient,
			ObjectProvider<ObservationRegistry> observationRegistry,
			ObjectProvider<DecisionModelObservationConvention> observationConvention) {
		return TypeSafeDecisionModel.builder(typeSafeClient)
			.observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
			.observationConvention(observationConvention.getIfAvailable())
			.build();
	}

}
