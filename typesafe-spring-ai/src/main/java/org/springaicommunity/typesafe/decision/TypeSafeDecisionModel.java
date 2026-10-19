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

package org.springaicommunity.typesafe.decision;

import java.util.Objects;

import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.observation.DecisionModelObservationContext;
import org.springframework.ai.decision.observation.DecisionModelObservationConvention;
import org.springframework.ai.decision.observation.DecisionModelObservationDocumentation;
import org.springframework.ai.decision.observation.DefaultDecisionModelObservationConvention;
import org.springframework.util.Assert;

/**
 * Spring AI {@link DecisionModel} backed by the TypeSafe System One API, through a
 * {@link TypeSafeClient}. A yes/no question is sent as a {@code noul}, a choice as a
 * {@code choice} and a score as a {@code score}; the answers come back with the
 * probabilities and confidence Jev reports.
 *
 * <p>
 * The connection (API key, base URL, retries, timeout) belongs to the client. The only
 * option the model takes is the model name, which defaults to the client's default model
 * and can be overridden per prompt with {@link DecisionOptions}.
 *
 * @author Thomas Vitale
 */
public final class TypeSafeDecisionModel implements DecisionModel {

	/**
	 * The provider name recorded in observations.
	 */
	public static final String PROVIDER = "typesafe";

	private static final DecisionModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultDecisionModelObservationConvention();

	private final TypeSafeClient typeSafeClient;

	private final DecisionOptions options;

	private final ObservationRegistry observationRegistry;

	private final DecisionModelObservationConvention observationConvention;

	private TypeSafeDecisionModel(Builder builder) {
		this.typeSafeClient = builder.typeSafeClient;
		DecisionOptions options = Objects.requireNonNullElseGet(builder.options,
				() -> DecisionOptions.builder().build());
		this.options = options.getModel() != null ? options
				: options.mutate().model(this.typeSafeClient.defaultModel()).build();
		this.observationRegistry = Objects.requireNonNullElse(builder.observationRegistry, ObservationRegistry.NOOP);
		this.observationConvention = Objects.requireNonNullElse(builder.observationConvention,
				DEFAULT_OBSERVATION_CONVENTION);
	}

	public static Builder builder(TypeSafeClient typeSafeClient) {
		return new Builder(typeSafeClient);
	}

	@Override
	public DecisionResponse call(DecisionPrompt prompt) {
		Assert.notNull(prompt, "prompt must not be null");

		DecisionOptions.Builder<?> optionsBuilder = this.options.mutate();
		if (prompt.getOptions() != null) {
			optionsBuilder.combineWith(prompt.getOptions().mutate());
		}
		DecisionOptions options = optionsBuilder.build();
		String model = options.getModel();
		Assert.state(model != null, "model must not be null");

		SystemOneRequest request = SystemOneRequest.builder()
			.state(TypeSafeDecisionMapper.state(prompt.getInstructions()))
			.model(model)
			.questions(TypeSafeDecisionMapper.questions(prompt))
			.build();

		// The observation sees the merged options, so the model name is always recorded.
		DecisionPrompt observedPrompt = prompt.mutate().options(options).build();
		var observationContext = DecisionModelObservationContext.builder()
			.decisionPrompt(observedPrompt)
			.provider(PROVIDER)
			.build();

		return Objects.requireNonNull(DecisionModelObservationDocumentation.DECISION_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				SystemOneResponse response = this.typeSafeClient.systemOne(request);
				DecisionResponse decisionResponse = TypeSafeDecisionMapper.response(prompt, response);
				observationContext.setResponse(decisionResponse);
				return decisionResponse;
			}));
	}

	/**
	 * The default options, with the model name applied.
	 */
	public DecisionOptions getOptions() {
		return this.options;
	}

	public static final class Builder {

		private final TypeSafeClient typeSafeClient;

		private @Nullable DecisionOptions options;

		private @Nullable ObservationRegistry observationRegistry;

		private @Nullable DecisionModelObservationConvention observationConvention;

		private Builder(TypeSafeClient typeSafeClient) {
			Assert.notNull(typeSafeClient, "typeSafeClient must not be null");
			this.typeSafeClient = typeSafeClient;
		}

		/**
		 * The default options. When they name no model, the client's default model is
		 * used.
		 */
		public Builder options(@Nullable DecisionOptions options) {
			this.options = options;
			return this;
		}

		public Builder observationRegistry(@Nullable ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		public Builder observationConvention(@Nullable DecisionModelObservationConvention observationConvention) {
			this.observationConvention = observationConvention;
			return this;
		}

		public TypeSafeDecisionModel build() {
			return new TypeSafeDecisionModel(this);
		}

	}

}
