Assess only the listed tokens contributed by one player in the context of the final shared story. Deduct points for
grammatical, semantic, or logical errors caused by those contributions. Return a JSON object containing only one key
called "value" of type integer from 0 (no errors) to 100 (severe repeated errors). Do not reward story similarity here.

PLAYER TOKENS:
{{insertedTokens}}

FINAL SHARED STORY:
{{sharedStory}}