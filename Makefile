.DEFAULT_GOAL := help
.PHONY: help check docs-check lint scripts-test build swift swift-sim ios-sample e2e-android e2e-ios swift-proto desktop server fake-traffic branch-check debt-check loop loop-watch loop-worktree

# Default macOS location; an ANDROID_HOME from the environment wins.
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
export ANDROID_HOME

help: ## List targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

check: docs-check lint scripts-test build swift ## All gates (grows as modules land)

docs-check: ## Docs frontmatter, indexes and ADR/DEBT references
	@scripts/docs_check.sh

lint: ## ktlint via Spotless (fix with ./gradlew spotlessApply)
	@./gradlew spotlessCheck

scripts-test: ## Bash tests of the loop scripts (ADR-0013)
	@scripts/test_loop.sh
	@scripts/test_debt_check.sh

build: ## Compile and test every Gradle module, build-logic included
	@./gradlew build

swift: ## iOS SDK gates: generated code fresh, swift-format, swift test, simulator tests (macOS only, ADR-0037)
	@scripts/swift_check.sh

swift-sim: ## iOS SDK tests hosted in an app on an iPhone simulator (SIM_DEVICE="iPhone 17", ADR-0047)
	@scripts/swift_sim_check.sh

ios-sample: ## Build samples/ios in Debug and Release, keys and SDK in Debug only (ADR-0050)
	@scripts/ios_sample_check.sh

e2e-android: ## Sample on a booted emulator against the headless server, envelopes asserted (ANDROID_SERIAL, ADR-0056)
	@scripts/e2e_android.sh

e2e-ios: ## Sample on an iPhone simulator against the headless server, envelopes asserted (SIM_DEVICE, ADR-0057)
	@scripts/e2e_ios.sh

swift-proto: ## Regenerate sdk/ios swift-protobuf code from protocol/proto (ADR-0039)
	@scripts/swift_proto_gen.sh

desktop: ## Run the desktop app (listens for devices on port 47474)
	@./gradlew -q :desktop:app:run

server: ## Headless desktop server, logs every envelope (ARGS="--port N")
	@./gradlew -q :desktop:server:run $(if $(ARGS),--args="$(ARGS)")

fake-traffic: ## Replay protocol fixtures to a desktop (ARGS="--host H --port N --name S")
	@./gradlew -q :tools:fake-client:run $(if $(ARGS),--args="$(ARGS)")

branch-check: ## Current branch follows <type>/<kebab-slug>
	@scripts/branch_check.sh

debt-check: ## Open DEBTs whose trigger names a file this branch changes (BASE=origin/dev, ADR-0051)
	@scripts/debt_check.sh $(or $(BASE),origin/dev)

loop: ## Run /next in fresh sessions in ../app-monitor-loop until it stops
	@scripts/loop.sh

loop-watch: ## Follow the running loop session live
	@scripts/loop_watch.sh

loop-worktree: ## Create the loop worktree (open `claude` there once to trust it)
	@scripts/loop.sh --worktree

