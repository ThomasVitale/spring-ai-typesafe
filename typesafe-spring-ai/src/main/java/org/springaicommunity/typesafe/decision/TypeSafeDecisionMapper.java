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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springaicommunity.typesafe.JsonContent;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import org.springaicommunity.typesafe.question.Score;
import org.springaicommunity.typesafe.response.Answer;
import org.springaicommunity.typesafe.response.NoulAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.content.Media;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.DecisionResponseMetadata;
import org.springframework.ai.decision.answer.ChoiceAnswer;
import org.springframework.ai.decision.answer.DecisionAnswer;
import org.springframework.ai.decision.answer.ScoreAnswer;
import org.springframework.ai.decision.answer.YesNoAnswer;
import org.springframework.ai.decision.input.ConversationInput;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.input.FieldsInput;
import org.springframework.ai.decision.input.TextInput;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.decision.question.ScoreLevel;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.util.Assert;

/**
 * Maps a {@link DecisionPrompt} to a System One request and the response back to a
 * {@link DecisionResponse}.
 *
 * <p>
 * The input becomes the request state as it is: a text as a string, fields as a JSON
 * object, a conversation as {@code {"messages": [{"role", "content"}, ...]}}. Media is
 * rejected, since the client doesn't send image assets yet. A {@link YesNoQuestion}
 * becomes a {@code noul} with its criteria, a {@link ChoiceQuestion} a {@code choice}
 * and a {@link ScoreQuestion} a {@code score} whose levels carry their description.
 *
 * @author Thomas Vitale
 */
final class TypeSafeDecisionMapper {

	static final String MESSAGES_FIELD = "messages";

	private TypeSafeDecisionMapper() {
	}

	// Request

	static JsonContent state(DecisionInput input) {
		if (input instanceof TextInput text) {
			Assert.isTrue(text.getMedia().isEmpty(), "TypeSafeDecisionModel decides about text; media is not supported");
			return JsonContent.of(text.getText());
		}
		if (input instanceof FieldsInput fields) {
			fields.getFields()
				.forEach((name, value) -> Assert.isTrue(!(value instanceof Media),
						() -> "TypeSafeDecisionModel decides about text; field '" + name + "' carries media"));
			return JsonContent.of(fields.getFields());
		}
		if (input instanceof ConversationInput conversation) {
			List<Map<String, Object>> messages = new ArrayList<>();
			for (Message message : conversation.getMessages()) {
				messages.add(message(message));
			}
			return JsonContent.object(MESSAGES_FIELD, messages);
		}
		throw new IllegalArgumentException("Unsupported decision input: " + input.getClass().getName());
	}

	private static Map<String, Object> message(Message message) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("role", message.getMessageType().getValue());
		if (message instanceof UserMessage user) {
			Assert.isTrue(user.getMedia().isEmpty(),
					"TypeSafeDecisionModel decides about text; a user message carries media");
		}
		if (message instanceof ToolResponseMessage toolResponse) {
			entry.put("tool_results", toolResponse.getResponses().stream().map(response -> {
				Map<String, Object> result = new LinkedHashMap<>();
				result.put("id", response.id());
				result.put("name", response.name());
				result.put("result", response.responseData());
				return result;
			}).toList());
			return entry;
		}
		String text = message.getText();
		entry.put("content", text != null ? text : "");
		if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
			entry.put("tool_calls", assistant.getToolCalls().stream().map(toolCall -> {
				Map<String, Object> call = new LinkedHashMap<>();
				call.put("id", toolCall.id());
				call.put("name", toolCall.name());
				call.put("arguments", toolCall.arguments());
				return call;
			}).toList());
		}
		return entry;
	}

	static Map<String, Question> questions(DecisionPrompt prompt) {
		Map<String, Question> questions = new LinkedHashMap<>();
		prompt.getQuestions().forEach((name, question) -> questions.put(name, question(name, question)));
		return questions;
	}

	static Question question(String name, DecisionQuestion question) {
		if (question instanceof YesNoQuestion yesNo) {
			Noul.Builder builder = Noul.builder().instructions(yesNo.getText());
			if (yesNo.getWhenYes() != null) {
				builder.whenTrue(yesNo.getWhenYes());
			}
			if (yesNo.getWhenNo() != null) {
				builder.whenFalse(yesNo.getWhenNo());
			}
			return builder.build();
		}
		if (question instanceof ChoiceQuestion choice) {
			Choice.Builder builder = Choice.builder().instructions(choice.getText());
			choice.getOptions().forEach((option, description) -> {
				if (description != null) {
					builder.option(option, description);
				}
				else {
					builder.option(option);
				}
			});
			return builder.build();
		}
		if (question instanceof ScoreQuestion score) {
			Score.Builder builder = Score.builder().instructions(score.getText());
			for (ScoreLevel level : score.getLevels()) {
				if (level.getDescription() != null) {
					builder.level(JsonContent.object("label", level.getLabel(), "description", level.getDescription()));
				}
				else {
					builder.level(level.getLabel());
				}
			}
			return builder.build();
		}
		throw new IllegalArgumentException("Unsupported decision question '" + name + "' of type "
				+ question.getClass().getName() + ": TypeSafe supports yes/no, choice and score questions");
	}

	// Response

	static DecisionResponse response(DecisionPrompt prompt, SystemOneResponse response) {
		DecisionResponse.Builder builder = DecisionResponse.builder(prompt)
			.metadata(DecisionResponseMetadata.builder()
				.id(response.requestId())
				.model(response.model())
				.usage(new DefaultUsage(response.usage().inputTokens(), response.usage().outputTokens(), null,
						response.usage()))
				.build());
		response.answers()
			.forEach((name, answer) -> builder.answer(name, answer(name, answer, prompt.getQuestions().get(name))));
		return builder.build();
	}

	private static DecisionAnswer answer(String name, Answer answer, @Nullable DecisionQuestion question) {
		if (answer instanceof NoulAnswer noul) {
			return YesNoAnswer.builder().probability(noul.value()).build();
		}
		if (answer instanceof org.springaicommunity.typesafe.response.ChoiceAnswer choice) {
			return ChoiceAnswer.builder()
				.value(choice.value())
				.probabilities(choice.probabilities())
				.confidence(choice.confidence())
				.build();
		}
		if (answer instanceof org.springaicommunity.typesafe.response.ScoreAnswer score) {
			int levels = question instanceof ScoreQuestion scoreQuestion ? scoreQuestion.getLevels().size()
					: score.maxLevel() + 1;
			List<Double> probabilities = new ArrayList<>(Collections.nCopies(levels, 0.0));
			score.probabilities().forEach((level, probability) -> {
				Assert.state(level >= 0 && level < levels,
						() -> "The answer to '" + name + "' reports a probability for level " + level + " of " + levels);
				probabilities.set(level, probability);
			});
			return ScoreAnswer.builder()
				.value(score.value())
				.probabilities(probabilities)
				.confidence(score.confidence())
				.build();
		}
		throw new IllegalStateException("Unsupported answer type for question '" + name + "': " + answer);
	}

}
