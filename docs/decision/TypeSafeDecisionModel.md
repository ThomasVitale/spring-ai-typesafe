# TypeSafeDecisionModel

**Jev behind Spring AI's `DecisionModel` API.** Ask typed yes/no, choice and score
questions through the portable API, and switch between Jev and other decision models
without changing your code.

## A decision model, not a chat model

Spring AI's `DecisionModel` is made for models like Jev: it takes an input and a set of
typed questions, and returns one answer per question with the probabilities the model
assigned. Nothing is generated and nothing is parsed.

- **Portable questions.** `YesNoQuestion`, `ChoiceQuestion` and `ScoreQuestion` map one to
  one onto Jev's `noul`, `choice` and `score` primitives, criteria included.
- **Portable answers.** `YesNoAnswer`, `ChoiceAnswer` and `ScoreAnswer` carry the
  probabilities and the confidence Jev reports.
- **Validated responses.** Every answer is checked against its question before you see
  it: a choice is one of the offered options, a score sits within the levels.
- **Standard observability.** Calls are recorded as `gen_ai.client.operation` observations
  with the `decision` operation name.

## Quick Start

```java
TypeSafeClient client = TypeSafeClient.builder().build(); // reads TYPESAFE_API_KEY
DecisionModel decisionModel = TypeSafeDecisionModel.builder(client).build();

DecisionResponse response = decisionModel.call(DecisionPrompt.builder()
    .text("Help! My payouts are late again and nobody answers my emails.")
    .question("urgent", YesNoQuestion.builder()
        .text("Is this request urgent?")
        .whenYes("Money or access is blocked")
        .whenNo("The customer is asking a general question")
        .build())
    .question("team", ChoiceQuestion.builder()
        .text("Which team should handle this request?")
        .option("billing", "Invoices, charges, payouts and refunds")
        .option("technical", "Bugs, outages and integrations")
        .option("other")
        .build())
    .question("mood", ScoreQuestion.of("How is the customer's mood?", "calm", "annoyed", "angry"))
    .build());

boolean urgent = response.getYesNo("urgent").isYes();          // e.g. true (0.97)
ChoiceAnswer team = response.getChoice("team");                 // e.g. billing, 0.91, confidence 0.88
double mood = response.getScore("mood").getValue();             // e.g. 1.8, between annoyed and angry
```

For a single text and the default options, `decisionModel.call(text, questions)` does the
same in one line.

## What Jev sees

| Spring AI | System One request |
|-----------|--------------------|
| `DecisionInput.text("…")` | `"state": "…"` |
| `DecisionInput.fields(Map)` | `"state": {…}` — the fields as a JSON object, in order |
| `DecisionInput.conversation(List<Message>)` | `"state": {"messages": [{"role", "content"}, …]}`, with `tool_calls` and `tool_results` where a message has them |
| `YesNoQuestion` | `{"type": "noul", "instructions": text, "criteria": {"true": whenYes, "false": whenNo}}` |
| `ChoiceQuestion` | `{"type": "choice", "instructions": text, "criteria": {option: description, …}}` |
| `ScoreQuestion` | `{"type": "score", "instructions": text, "criteria": [label or {"label", "description"}, …]}` |

Questions are sent in the order they were added, and the answers come back in the same
order.

## What comes back

| Question | Answer | From the wire |
|----------|--------|---------------|
| `YesNoQuestion` | `YesNoAnswer.getProbability()` | `noul` |
| `ChoiceQuestion` | `ChoiceAnswer.getValue()`, `getProbabilities()`, `getConfidence()` | `choice`, `probabilities`, `confidence` |
| `ScoreQuestion` | `ScoreAnswer.getValue()`, `getProbabilities()` by level, `getConfidence()` | `score`, `probabilities`, `confidence` |

`DecisionResponse.getMetadata()` carries the request id, the model that answered and the
token usage. Probabilities are what Jev assigned; confidence is Jev's own estimate, read
[Confidence](../concepts/confidence.md) before you threshold on it.

## Builder Configuration

| Builder method | Default | Description |
|----------------|---------|-------------|
| `builder(TypeSafeClient)` | — | The client that carries the API key, base URL, retries and timeout |
| `options(DecisionOptions)` | the client's default model | Default options; a prompt's options override only what they set |
| `observationRegistry(…)` | `ObservationRegistry.NOOP` | Where observations are recorded |
| `observationConvention(…)` | Spring AI's default | Custom observation names and keys |

Pick the model per call with `DecisionOptions.builder().model("jev-1.13.0").build()` on
the prompt.

## What it refuses

| Input | Why |
|-------|-----|
| Media on a text, a field or a user message | Images go through the `assets` API, which the client doesn't send yet |
| A question type other than yes/no, choice or score | Jev has no primitive for it |

A response that doesn't answer the prompt (a missing answer, a choice outside the options,
a score outside the levels) fails with an `IllegalStateException`; API errors surface as the
client's `TypeSafeException`s.

## Observability

| Key | Value |
|-----|-------|
| `gen_ai.operation.name` | `decision` |
| `gen_ai.system` | `typesafe` |
| `gen_ai.request.model` / `gen_ai.response.model` | The model asked for and the one that answered |
| `gen_ai.response.id` | The request id |
| `gen_ai.usage.input_tokens`, `output_tokens`, `total_tokens` | Token usage, once per request |

Add `spring-ai-autoconfigure-model-decision-observation` to record token usage metrics.

## With Spring Boot

The [starter](../client/SpringBootStarter.md) auto-configures a `TypeSafeDecisionModel`
bean on top of the `TypeSafeClient` when `typesafe-spring-ai` is on the classpath:

```properties
spring.ai.typesafe.api-key=${TYPESAFE_API_KEY}
spring.ai.typesafe.model=jev-latest
```

```java
@Service
class TicketTriage {

    private final DecisionModel decisionModel;

    TicketTriage(DecisionModel decisionModel) {
        this.decisionModel = decisionModel;
    }
}
```

With several decision models on the classpath, `spring.ai.model.decision=typesafe` selects
this one, and any other value backs it off.

## When to use something else

- Pass/fail verdicts with feedback and retries: [JevJudge](../judge/JevJudge.md) and
  [JevSelfRefineAdvisor](../judge/JevSelfRefineAdvisor.md).
- Screening every chat turn: [JevGuardrailAdvisor](../guardrails/JevGuardrailAdvisor.md).
- Scoring many items in parallel: [Batches](../client/Batches.md).

## See Also

- [The Three Primitives](../concepts/primitives.md)
- [Confidence](../concepts/confidence.md)
- [TypeSafeClient](../client/TypeSafeClient.md)
