# Core beliefs

How work is done in this repository. Taken from OpenAI's "Harness engineering" report (February 2026) and GitHub's guidance for Copilot coding agent, adapted to this service.

- **Humans steer, agents execute.** People decide priorities, turn user needs into acceptance criteria and review outcomes. Copilot writes code, tests and documentation.
- **The repository is the system of record.** If a decision lives only in a chat, a meeting or someone's head, the agent cannot see it. Write it into this repository.
- **A map, not a manual.** `.github/copilot-instructions.md` stays short and points to deeper documents. Detail goes into `docs/`.
- **Enforce invariants, not implementations.** Rules that must always hold are tests or lints with a message that says how to fix a violation. How something is built inside those rules is free.
- **Promote the rule into code.** When the same review comment appears twice, it becomes a test, a lint or a line in a document.
- **Parse data at the boundary.** Incoming Kafka messages and HTTP requests become typed records at the edge; nothing inside works on guessed shapes.
- **Prefer boring technology.** Well-known, stable libraries are easier for agents to use correctly.
- **When the agent struggles, something is missing.** Ask which tool, guardrail or document is missing, and add it to the repository.
