SHELL := /bin/sh

.PHONY: setup test test-core test-go test-e2e test-android android-apk test-docker test-live smoke clean package

setup:
	./scripts/setup.sh $${DOMAIN:-pocketexit.local}

test: test-core
	@echo "Core tests passed. Run 'make test-android' and 'make test-docker' where those toolchains are installed."

test-core:
	./scripts/test.sh

test-go:
	cd backend && go test ./... && go vet ./... && go test -race ./...

# The two process-level tests scripts/test.sh runs after the smoke test, on
# their own: both build the backend and drive it over a real socket, so they
# need nothing beyond Go, curl and python3.
test-e2e:
	./.github/e2e/personal-smoke.sh
	python3 .github/e2e/socks-e2e.py

test-android:
	cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug

android-apk:
	docker build -t pocketexit-android-builder android
	docker run --rm --user "$$(id -u):$$(id -g)" \
		-e HOME=/tmp -e GRADLE_USER_HOME=/tmp/gradle \
		-v "$(CURDIR)/android:/workspace" pocketexit-android-builder
	@echo "APK: android/app/build/outputs/apk/debug/app-debug.apk"

# The system test owns the stack: it brings it up, validates the gateway
# configuration, drives real SOCKS5 and agent traffic through nginx, and tears
# it down again. It needs a .env, which scripts/setup.sh writes.
test-docker:
	docker compose config >/dev/null
	docker compose build
	python3 .github/e2e/system-test.py

test-live:
	./scripts/live-phone-tests.sh

smoke:
	./scripts/smoke-backend.sh

clean:
	rm -f backend/pocketexit backend/server backend/coverage.out
	rm -rf android/.gradle android/build android/app/build

package: clean
	./scripts/package.sh
