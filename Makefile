.DEFAULT_GOAL := help
.PHONY: help check check-full samples android-samples docs-check designsystem-check lint scripts-test build swift swift-sim ios-sample kmp-sample e2e-android e2e-ios e2e-kmp e2e-kmp-android e2e-kmp-ios publish-dry-run swift-proto desktop server fake-traffic branch-check debt-check loop loop-watch loop-worktree

# Default macOS location; an ANDROID_HOME from the environment wins.
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
export ANDROID_HOME

help: ## List targets
	@grep -E '^[a-z0-9-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-16s %s\n", $$1, $$2}'

check: docs-check designsystem-check lint scripts-test build swift ## Fast suite for PRs to dev: no simulator, sample app or end to end (ADR-0076)

check-full: check swift-sim samples ## Fast suite plus simulator tests and sample apps; end to ends stay separate (ADR-0076)

docs-check: ## Docs frontmatter, indexes and ADR/DEBT references
	@scripts/docs_check.sh

designsystem-check: ## Desktop screens use the design system's tokens and components only (ADR-0133)
	@scripts/designsystem_check.sh

lint: ## ktlint via Spotless (fix with ./gradlew spotlessApply)
	@./gradlew spotlessCheck

scripts-test: ## Bash tests of the loop and end to end scripts (ADR-0013)
	@scripts/test_loop.sh
	@scripts/test_debt_check.sh
	@scripts/test_sim_list.sh
	@scripts/test_e2e_lib.sh
	@scripts/test_designsystem_check.sh
	@scripts/test_swift_proto_gen.sh

# ADR-0076: the sample apps build in make samples and the release gate.
SAMPLE_BUILDS := -x :samples:android:build -x :samples:kmp:shared:build -x :samples:kmp:androidApp:build

build: ## Compile and test every Gradle module but the sample apps, build-logic included
	@./gradlew build $(SAMPLE_BUILDS)

swift: ## iOS SDK host gates: generated code fresh, swift-format, swift test, public API (macOS only, ADR-0037)
	@scripts/swift_check.sh

# Simulator targets skip, with one line, where they cannot run (Linux CI, no XcodeGen).
XCODEGEN_HOST = [ "$$(uname -s)" = Darwin ] && command -v xcodegen >/dev/null

swift-sim: ## iOS SDK tests hosted in an app on an iPhone simulator (SIM_DEVICE="iPhone 17", ADR-0047)
	@if $(XCODEGEN_HOST); then scripts/swift_sim_check.sh; else echo "swift-sim: SKIPPED (needs macOS and 'brew install xcodegen'; ADR-0047)"; fi

ios-sample: ## Build samples/ios in Debug and Release, keys and SDK in Debug only (ADR-0050)
	@if $(XCODEGEN_HOST); then scripts/ios_sample_check.sh; else echo "ios-sample: SKIPPED (needs macOS and 'brew install xcodegen'; ADR-0050)"; fi

samples: android-samples ios-sample kmp-sample ## Every sample app: Gradle builds and release checks, iOS and KMP (ADR-0076)

android-samples: ## Android sample: build, lint, host tests, release holds no SDK; the Gradle plugin's TestKit tests (ADR-0030, ADR-0112)
	@./gradlew :samples:android:build :gradle-plugin:functionalTest

kmp-sample: ## Build samples/kmp, Android and iOS, debug and release, SDK in debug only (ADR-0073)
	@scripts/kmp_sample_check.sh

e2e-android: ## Sample on a booted emulator against the headless server, envelopes asserted (ANDROID_SERIAL, ADR-0056)
	@scripts/e2e_android.sh

e2e-ios: ## Sample on an iPhone simulator against the headless server, envelopes asserted (SIM_DEVICE, ADR-0057)
	@scripts/e2e_ios.sh

e2e-kmp: e2e-kmp-android e2e-kmp-ios ## KMP sample on an emulator, then a simulator beside the Swift SDK, each exchange once (ADR-0075)

e2e-kmp-android: ## KMP sample on a booted emulator against the headless server, envelopes asserted (ANDROID_SERIAL)
	@scripts/e2e_kmp.sh android

e2e-kmp-ios: ## KMP sample on an iPhone simulator with the Swift SDK, each exchange once (SIM_DEVICE)
	@scripts/e2e_kmp.sh ios

publish-dry-run: ## SDKs to build/maven-dry-run, POMs checked; SwiftPM consumer in debug and release (ADR-0058)
	@scripts/publish_dry_run.sh

swift-proto: ## Regenerate sdk/ios protobuf types from protocol/proto (ADR-0160)
	@scripts/swift_proto_gen.sh

desktop: ## Run the desktop app (listens for devices on port 47474)
	@./gradlew -q :desktop:app:run

server: ## Headless desktop server, logs every envelope (ARGS="--port N")
	@./gradlew -q :desktop:server:run $(if $(ARGS),--args="$(ARGS)")

fake-traffic: ## Replay protocol fixtures to a desktop (ARGS="--host H --port N --name S")
	@./gradlew -q :tools:fake-client:run $(if $(ARGS),--args="$(ARGS)")

branch-check: ## Current branch follows <type>/<kebab-slug>
	@scripts/branch_check.sh

debt-check: ## Open DEBTs whose trigger names a file this branch changes (BASE=origin/dev; exit 2 if it does not resolve, ADR-0061)
	@scripts/debt_check.sh $(or $(BASE),origin/dev)

loop: ## Run /next in fresh sessions in ../app-monitor-loop until it stops
	@scripts/loop.sh

loop-watch: ## Follow the running loop session live
	@scripts/loop_watch.sh

loop-worktree: ## Create the loop worktree (open `claude` there once to trust it)
	@scripts/loop.sh --worktree

