.DEFAULT_GOAL := help
.PHONY: help check docs-check lint build server fake-traffic branch-check loop loop-watch loop-worktree

# Default macOS location; an ANDROID_HOME from the environment wins.
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
export ANDROID_HOME

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

check: docs-check lint build ## All gates (grows as modules land)

docs-check: ## Docs frontmatter, indexes and ADR/DEBT references
	@scripts/docs_check.sh

lint: ## ktlint via Spotless (fix with ./gradlew spotlessApply)
	@./gradlew spotlessCheck

build: ## Compile and test every Gradle module, build-logic included
	@./gradlew build

server: ## Headless desktop server, logs every envelope (ARGS="--port N")
	@./gradlew -q :desktop:server:run --args="$(ARGS)"

fake-traffic: ## Replay protocol fixtures to a desktop (ARGS="--host H --port N --name S")
	@./gradlew -q :tools:fake-client:run --args="$(ARGS)"

branch-check: ## Current branch follows <type>/<kebab-slug>
	@scripts/branch_check.sh

loop: ## Run /next in fresh sessions in ../app-monitor-loop until it stops
	@scripts/loop.sh

loop-watch: ## Follow the running loop session live
	@scripts/loop_watch.sh

loop-worktree: ## Create the loop worktree (open `claude` there once to trust it)
	@git fetch -q origin --prune
	@test -d ../$(notdir $(CURDIR))-loop || git worktree add -q --detach ../$(notdir $(CURDIR))-loop origin/dev
	@echo "worktree: $(abspath ../$(notdir $(CURDIR))-loop)"

