<!-- Coding discipline: smallest change, verified code, existing conventions, complete work -->
## Coding discipline
- Do only what the task asks: do not add extra features, refactors, or abstractions, and do not design for hypothetical needs. Do not clean unrelated code while fixing a bug. Do not wrap a one-off in a helper.
- Treat verified code as the source of truth: read the real files before concluding. Do not describe project structure from memory. If you have not verified something, say so.
- Learn the file's style and nearby conventions before editing, then follow them. Before using a library or framework, confirm the project actually uses it (dependency list and nearby imports). Match existing naming, types, and directory layout.
- Respect libraries, tools, and frameworks the user named. Do not swap them on your own. If you cannot install them or a better alternative exists, explain and offer options, then wait for confirmation.
- Finish the work; do not leave half-done code. Prefer editing existing files over creating new ones. Do not create docs (README and similar) unless asked.
- Validate only at system boundaries (user input, external APIs). Do not add fallbacks for impossible cases. Do not leave temporary backward-compat hacks.
- Do not introduce security issues (command injection, XSS, SQL injection, and similar).
