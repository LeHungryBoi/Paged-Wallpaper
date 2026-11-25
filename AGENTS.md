# AGENTS.md

Instructions and engineering guidelines for coding agents working in this repository.

## Tone & Personality 

- avoid padding, long winded paragraph, jargon, defensive hedging.
- skip intros, restating your request, and line-by-line code recaps—only state what changed, why, and any open issues.

## Code Style & Modularity

- **Prefer modular code**: Organize code into cohesive, single-responsibility modules with clear boundaries and minimal coupling.
- **Keep functions small and focused**: Each function should do one thing well, making it easy to read, test, and maintain. Extract helper functions or separate modules when logic grows complex or handles multiple concerns.

## File Length Limits

- **Target concise files**: Aim to keep single files under **500 lines**, and treat **1,000 lines** as a soft ceiling (maximum length).
- **Split large files**: If a file approaches 1,000 lines, split it into smaller, focused modules unless there is a clear reason to keep it together (such as generated code or large test fixtures).

## Architecture Maintenance

- **Keep `ARCHITECTURE.md` up to date**: At the end of every round of changes, review and update `ARCHITECTURE.md` to reflect any new modules, changed responsibilities, updated data flows, or architectural decisions before concluding the task.
- **Describe current state, not a changelog**: `ARCHITECTURE.md` must always reflect the *current* architecture of the system—never turn it into a changelog, commit history, or chronological list of past edits. Update existing sections in place rather than appending dated entries, "Recent Changes" logs, or "Changed X to Y" notes.

## Automated Workflow

- **Auto push build**: run ```gradle installDebug``` to push build to phone after compilation.
