# Default Models

Under "Settings → Default Models", besides the default model for new sessions, you can also designate dedicated models for a few specific purposes.

## Image Recognition Model

Decides which model the AI uses to look at images.

- **Follow the current chat model** (default): images are read with your currently selected chat model.
- **Dedicated model**: a fixed model is used for images, regardless of chat model switching. Useful when your chat model doesn't support images, or when you want a cheaper model to handle them.

Each model in the list is tagged to show whether it supports image input, for your reference. Note there is **no hard blocking** here: if you pick a model without image support, recognition will fail, the AI receives the error and handles it itself (usually by suggesting you switch models).

Two things worth knowing about how image recognition is used:

- The AI can give the image model up to 4 or 5 images at a time for comparative analysis, and you can keep asking follow-ups in the same image session—the model retains the images and Q&A it has already seen.
- Images are compressed on demand by default to save tokens; you can also request original images be sent as-is, or a lower-resolution mode that saves even more tokens.

## Image Generation Model

Sets the model used by the `generateImage` tool; both OpenAI- and Gemini-compatible interfaces are supported.

- The OpenAI-compatible interface supports parameters such as size, count, quality, background, style, and output format.
- The Gemini-compatible interface uses the model's default size and resolution; generating multiple images makes multiple calls.
- Images are saved to `~/.xencode/generated-images/` by default, but a workspace path can be specified. The per-image limit is 20MB, and the per-call total limit is 48MB.

## Generating Images in Chat with Image Models

After connecting a chat model with image output via the Gemini-compatible interface, you can generate images directly, upload reference images, and keep refining the results in subsequent turns.

## Title Summarization Model

After the first message is sent in a new session, XenCode automatically generates a title for it. Here you can designate a model dedicated to this task.

- **Follow the current chat model** (default).
- **Dedicated model**: a cheap, fast small model is recommended—titles don't need a good model.

Title generation runs asynchronously in the background and won't slow your conversation. If generation fails or the result can't be fetched, the temporary title (the first sentence, truncated) is kept, and usage is unaffected.

The prompt used for title generation can also be customized; see "Custom Prompts" for how.
