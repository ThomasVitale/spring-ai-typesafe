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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.TypeSafeConstants;
import org.springaicommunity.typesafe.TypeSafeModels;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live tests for {@link TypeSafeDecisionModel} against the Jev API. They assert on
 * outcomes relative to each other, never on exact probabilities.
 *
 * @author Thomas Vitale
 */
@EnabledIfEnvironmentVariable(named = TypeSafeConstants.API_KEY_ENV, matches = ".+",
		disabledReason = "Set TYPESAFE_API_KEY to run against the real Jev API")
class TypeSafeDecisionModelIT {

	private final TypeSafeDecisionModel model = TypeSafeDecisionModel
		.builder(TypeSafeClient.builder().defaultModel(TypeSafeModels.JEV_LATEST).build())
		.build();

	@Test
	void yesNo() {
		var question = YesNoQuestion.of("Is this message spam?");

		var spam = this.model.call("WIN A FREE CRUISE!!! Click here now to claim your prize", Map.of("spam", question));
		var notSpam = this.model.call("Hi Anna, are we still on for lunch tomorrow at noon?", Map.of("spam", question));

		assertThat(spam.getYesNo("spam").getProbability()).isGreaterThan(notSpam.getYesNo("spam").getProbability());
		assertThat(spam.getMetadata().getModel()).isNotBlank();
		assertThat(spam.getMetadata().getId()).isNotBlank();
		assertThat(spam.getMetadata().getUsage().getPromptTokens()).isPositive();
	}

	@Test
	void choiceAndScoreWithFieldsInput() {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("subject", "Payouts late again");
		fields.put("body", "This is the third time this month my payout is delayed. Fix it now.");

		var response = this.model.call(DecisionPrompt.builder()
			.fields(fields)
			.question("urgent",
					YesNoQuestion.builder()
						.text("Is the request urgent?")
						.whenYes("Money or access is blocked")
						.whenNo("A general question")
						.build())
			.question("team", ChoiceQuestion.of("Which team should handle it?", "billing", "technical", "sales"))
			.question("mood", ScoreQuestion.of("How is the customer's mood?", "calm", "annoyed", "angry"))
			.build());

		assertThat(response.getAnswers().keySet()).containsExactly("urgent", "team", "mood");
		assertThat(response.getYesNo("urgent").isYes()).isTrue();
		assertThat(response.getChoice("team").getValue()).isEqualTo("billing");
		assertThat(response.getChoice("team").getProbabilities()).containsKeys("billing", "technical", "sales");
		assertThat(response.getChoice("team").getConfidence()).isBetween(0.0, 1.0);
		assertThat(response.getScore("mood").getValue()).isGreaterThan(0.5);
		assertThat(response.getScore("mood").getProbabilities()).hasSize(3);
	}

	@Test
	void conversationInput() {
		var messages = List.of(new UserMessage("What is the capital of France?"),
				new AssistantMessage("The capital of France is Paris."));

		var response = this.model.call(DecisionInput.conversation(messages),
				Map.of("correct", YesNoQuestion.of("Is the assistant's last answer correct?")));

		assertThat(response.getYesNo("correct").isYes()).isTrue();
	}

}
