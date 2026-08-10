SHELL := /bin/sh

.PHONY: setup test test-core test-go test-android android-apk test-docker test-live smoke clean package

setup:
	./scripts/setup.sh $${DOMAIN:-pocketexit.local}

test: test-core
	@echo "Core tests passed. Run 'make test-android' and 'make test-docker' where those toolchains are installed."

test-core:
	./scripts/test.sh

test-go:
	cd backend && go test ./... && go vet ./... && go test -race ./...

test-android:
	cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug

android-apk:
	docker build -t pocketexit-android-builder android
	docker run --rm --user "$$(id -u):$$(id -g)" \
		-e HOME=/tmp -e GRADLE_USER_HOME=/tmp/gradle \
		-v "$(CURDIR)/android:/workspace" pocketexit-android-builder
	@echo "APK: android/app/build/outputs/apk/debug/app-debug.apk"

test-docker:
	docker compose config >/dev/null
	docker compose build
	docker compose up -d
	@i=0; until curl -kfsS https://127.0.0.1/api/v1/health >/dev/null; do \
		i=$$((i+1)); [ $$i -lt 30 ] || { docker compose logs; exit 1; }; sleep 1; \
	done
	docker compose exec -T nginx nginx -t
	docker compose down -v

test-live:
	./scripts/live-phone-tests.sh

smoke:
	./scripts/smoke-backend.sh

clean:
	rm -f backend/pocketexit backend/server backend/coverage.out
	rm -rf android/.gradle android/build android/app/build

package: clean
	./scripts/package.sh
