.DEFAULT_GOAL := help
.PHONY: help check docs-check branch-check loop

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

check: docs-check ## All gates (grows as modules land)

docs-check: ## Docs frontmatter, indexes and ADR/DEBT references
	@scripts/docs_check.sh

branch-check: ## Current branch follows <type>/<kebab-slug>
	@scripts/branch_check.sh

loop: ## Run /next in fresh sessions until it stops (MAX_ITERATIONS, MAX_BUDGET_USD)
	@scripts/loop.sh
