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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.MockTypeSafeServer;
import org.springaicommunity.typesafe.TypeSafeModels;
import org.springaicommunity.typesafe.exception.TypeSafeUnprocessableEntityException;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.input.DecisionInput;
import org.springframework.ai.decision.observation.DecisionModelMeterObservationHandler;
import org.springframework.ai.decision.question.ChoiceQuestion;
import org.springframework.ai.decision.question.DecisionQuestion;
import org.springframework.ai.decision.question.ScoreQuestion;
import org.springframework.ai.decision.question.YesNoQuestion;
import org.springframework.ai.observation.conventions.AiObservationMetricNames;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * Unit tests for {@link TypeSafeDecisionModel}, against a mocked System One server.
 *
 * @author Thomas Vitale
 */
class TypeSafeDecisionModelTests {

	private static final String TRIAGE_RESPONSE = """
			{"model":"jev-1.13.0","answers":{
			  "urgent":{"type":"noul","noul":0.97},
			  "team":{"type":"choice","choice":"billing",
			    "probabilities":{"billing":0.91,"technical":0.04,"sales":0.05},"confidence":0.88},
			  "mood":{"type":"score","score":1.8,
			    "legend":{"0":"calm","1":"annoyed","2":"angry"},
			    "probabilities":{"0":0.02,"1":0.16,"2":0.82},"confidence":0.7}
			},"usage":{"input_tokens":210,"output_tokens":18}}""";

	private static final String URGENT_RESPONSE = """
			{"model":"jev-1.13.0","answers":{"urgent":{"type":"noul","noul":0.5}},
			 "usage":{"input_tokens":10,"output_tokens":1}}""";

	private final MockTypeSafeServer mock = MockTypeSafeServer.create();

	private final DecisionPrompt triage = DecisionPrompt.builder()
		.text("Help! My payouts are late again")
		.question("urgent",
				YesNoQuestion.builder()
					.text("Is it urgent?")
					.whenYes("Money is blocked")
					.whenNo("A general question")
					.build())
		.question("team",
				ChoiceQuestion.builder()
					.text("Which team?")
					.option("billing", "Invoices and payouts")
					.option("technical")
					.option("sales", "Quotes")
					.build())
		.question("mood",
				ScoreQuestion.builder()
					.text("How is the mood?")
					.level("calm")
					.level("annoyed", "Impatient but polite")
					.level("angry")
					.build())
		.build();

	@Test
	void sendsTheQuestionsAsSystemOnePrimitivesAndMapsTheAnswersBack() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.model").value(TypeSafeModels.JEV_LATEST))
			.andExpect(jsonPath("$.state").value("Help! My payouts are late again"))
			.andExpect(jsonPath("$.questions.urgent.type").value("noul"))
			.andExpect(jsonPath("$.questions.urgent.instructions").value("Is it urgent?"))
			.andExpect(jsonPath("$.questions.urgent.criteria.true").value("Money is blocked"))
			.andExpect(jsonPath("$.questions.urgent.criteria.false").value("A general question"))
			.andExpect(jsonPath("$.questions.team.type").value("choice"))
			.andExpect(jsonPath("$.questions.team.criteria.billing").value("Invoices and payouts"))
			.andExpect(jsonPath("$.questions.team.criteria.technical").doesNotExist())
			.andExpect(MockTypeSafeServer.keyOrder("/questions/team/criteria", "billing", "technical", "sales"))
			.andExpect(jsonPath("$.questions.mood.type").value("score"))
			.andExpect(jsonPath("$.questions.mood.criteria[0]").value("calm"))
			.andExpect(jsonPath("$.questions.mood.criteria[1].label").value("annoyed"))
			.andExpect(jsonPath("$.questions.mood.criteria[1].description").value("Impatient but polite"))
			.andExpect(jsonPath("$.questions.mood.criteria[2]").value("angry"))
			.andExpect(MockTypeSafeServer.keyOrder("/questions", "urgent", "team", "mood"))
			.andRespond(MockTypeSafeServer.jsonResponse(TRIAGE_RESPONSE));

		DecisionResponse response = model().call(this.triage);

		assertThat(response.getAnswers().keySet()).containsExactly("urgent", "team", "mood");
		assertThat(response.getYesNo("urgent").getProbability()).isEqualTo(0.97);
		assertThat(response.getChoice("team").getValue()).isEqualTo("billing");
		assertThat(response.getChoice("team").getProbability()).isEqualTo(0.91);
		assertThat(response.getChoice("team").getRankedOptions()).containsExactly("billing", "sales", "technical");
		assertThat(response.getChoice("team").getConfidence()).isEqualTo(0.88);
		assertThat(response.getScore("mood").getValue()).isEqualTo(1.8);
		assertThat(response.getScore("mood").getNearestLevel()).isEqualTo(2);
		assertThat(response.getScore("mood").getProbabilities()).containsExactly(0.02, 0.16, 0.82);
		assertThat(response.getScore("mood").getConfidence()).isEqualTo(0.7);
		assertThat(response.getMetadata().getId()).isEqualTo("req_0123456789");
		assertThat(response.getMetadata().getModel()).isEqualTo("jev-1.13.0");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(210);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(18);
		this.mock.server().verify();
	}

	@Test
	void sendsFieldsAsAJsonObjectState() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.subject").value("Payouts late"))
			.andExpect(jsonPath("$.state.attempts").value(3))
			.andExpect(jsonPath("$.state.tags[1]").value("urgent"))
			.andExpect(MockTypeSafeServer.keyOrder("/state", "subject", "attempts", "tags"))
			.andRespond(MockTypeSafeServer.jsonResponse(URGENT_RESPONSE));
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("subject", "Payouts late");
		fields.put("attempts", 3);
		fields.put("tags", List.of("billing", "urgent"));

		model().call(DecisionInput.fields(fields), Map.of("urgent", YesNoQuestion.of("Urgent?")));

		this.mock.server().verify();
	}

	@Test
	void sendsAConversationAsAMessagesState() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.state.messages[0].role").value("system"))
			.andExpect(jsonPath("$.state.messages[0].content").value("Be brief"))
			.andExpect(jsonPath("$.state.messages[1].role").value("user"))
			.andExpect(jsonPath("$.state.messages[1].content").value("Weather in Paris?"))
			.andExpect(jsonPath("$.state.messages[2].role").value("assistant"))
			.andExpect(jsonPath("$.state.messages[2].content").value("Let me check."))
			.andExpect(jsonPath("$.state.messages[2].tool_calls[0].name").value("get_weather"))
			.andExpect(jsonPath("$.state.messages[2].tool_calls[0].arguments").value("{\"city\":\"Paris\"}"))
			.andExpect(jsonPath("$.state.messages[3].role").value("tool"))
			.andExpect(jsonPath("$.state.messages[3].tool_results[0].name").value("get_weather"))
			.andExpect(jsonPath("$.state.messages[3].tool_results[0].result").value("rainy"))
			.andExpect(jsonPath("$.state.messages[4].content").value("It is rainy in Paris."))
			.andExpect(MockTypeSafeServer.keyOrder("/state/messages/1", "role", "content"))
			.andRespond(MockTypeSafeServer.jsonResponse(URGENT_RESPONSE));
		var messages = List.of(new SystemMessage("Be brief"), new UserMessage("Weather in Paris?"),
				AssistantMessage.builder()
					.content("Let me check.")
					.toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "get_weather",
							"{\"city\":\"Paris\"}")))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "get_weather", "rainy")))
					.build(),
				new AssistantMessage("It is rainy in Paris."));

		model().call(DecisionInput.conversation(messages), Map.of("urgent", YesNoQuestion.of("Urgent?")));

		this.mock.server().verify();
	}

	@Test
	void usesTheClientDefaultModelUnlessTheOptionsNameOne() {
		assertThat(model().getOptions().getModel()).isEqualTo(TypeSafeModels.JEV_LATEST);

		var pinned = TypeSafeDecisionModel.builder(this.mock.client())
			.options(DecisionOptions.builder().model(TypeSafeModels.JEV_1_13_0).build())
			.build();
		assertThat(pinned.getOptions().getModel()).isEqualTo(TypeSafeModels.JEV_1_13_0);

		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andExpect(jsonPath("$.model").value(TypeSafeModels.JEV_PREVIEW))
			.andRespond(MockTypeSafeServer.jsonResponse(URGENT_RESPONSE));

		pinned.call(DecisionPrompt.builder()
			.text("x")
			.question("urgent", YesNoQuestion.of("Urgent?"))
			.options(DecisionOptions.builder().model(TypeSafeModels.JEV_PREVIEW).build())
			.build());

		this.mock.server().verify();
	}

	@Test
	void refusesMedia() {
		var image = new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] { 1 }));

		assertThatIllegalArgumentException()
			.isThrownBy(() -> model().call(DecisionInput.text("x", List.of(image)),
					Map.of("urgent", YesNoQuestion.of("Urgent?"))))
			.withMessageContaining("media");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> model().call(DecisionInput.fields(Map.of("photo", image)),
					Map.of("urgent", YesNoQuestion.of("Urgent?"))))
			.withMessageContaining("'photo'");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> model().call(
					DecisionInput.conversation(List.of(UserMessage.builder().text("Look").media(image).build())),
					Map.of("urgent", YesNoQuestion.of("Urgent?"))))
			.withMessageContaining("media");
	}

	@Test
	void refusesUnknownQuestionTypes() {
		DecisionQuestion custom = () -> "custom";

		assertThatIllegalArgumentException().isThrownBy(() -> model().call("x", Map.of("q", custom)))
			.withMessageContaining("Unsupported decision question 'q'");
	}

	@Test
	void rejectsAResponseThatDoesNotAnswerThePrompt() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse("""
					{"model":"jev-1.13.0","answers":{"team":{"type":"choice","choice":"other",
					 "probabilities":{"other":1.0},"confidence":1.0}},"usage":{"input_tokens":1,"output_tokens":1}}"""));

		assertThatIllegalStateException()
			.isThrownBy(() -> model().call("x", Map.of("team", ChoiceQuestion.of("Which team?", "billing", "technical"))))
			.withMessageContaining("'other'");
	}

	@Test
	void propagatesApiErrors() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.errorResponse(422, """
					{"detail":[{"type":"missing","loc":["body","questions"],"msg":"Field required"}]}"""));

		assertThatExceptionOfType(TypeSafeUnprocessableEntityException.class)
			.isThrownBy(() -> model().call("x", Map.of("urgent", YesNoQuestion.of("Urgent?"))));
	}

	@Test
	void recordsTokenUsageThroughTheDecisionObservation() {
		this.mock.server()
			.expect(requestTo(MockTypeSafeServer.SYSTEM_ONE_URL))
			.andRespond(MockTypeSafeServer.jsonResponse(TRIAGE_RESPONSE));
		var meterRegistry = new SimpleMeterRegistry();
		var observationRegistry = ObservationRegistry.create();
		observationRegistry.observationConfig()
			.observationHandler(new DecisionModelMeterObservationHandler(meterRegistry));
		var model = TypeSafeDecisionModel.builder(this.mock.client()).observationRegistry(observationRegistry).build();

		model.call(this.triage);

		assertThat(meterRegistry.find(AiObservationMetricNames.TOKEN_USAGE.value())
			.tag("gen_ai.token.type", "total")
			.tag("gen_ai.system", TypeSafeDecisionModel.PROVIDER)
			.tag("gen_ai.operation.name", "decision")
			.counter()).isNotNull().satisfies(counter -> assertThat(counter.count()).isEqualTo(228));
	}

	private TypeSafeDecisionModel model() {
		return TypeSafeDecisionModel.builder(this.mock.client()).build();
	}

}
