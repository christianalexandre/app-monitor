.DEFAULT_GOAL := help
.PHONY: help check docs-check lint build branch-check loop

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

check: docs-check lint build ## All gates (grows as modules land)

docs-check: ## Docs frontmatter, indexes and ADR/DEBT references
	@scripts/docs_check.sh

lint: ## ktlint via Spotless (fix with ./gradlew spotlessApply)
	@./gradlew spotlessCheck

build: ## Compile and test every Gradle module, build-logic included
	@./gradlew build

branch-check: ## Current branch follows <type>/<kebab-slug>
	@scripts/branch_check.sh

loop: ## Run /next in fresh sessions until it stops (MAX_ITERATIONS, MAX_BUDGET_USD)
	@scripts/loop.sh
