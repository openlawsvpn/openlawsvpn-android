.PHONY: init submodules deps release bundle lint test clean clean-deps-build wrapper

# Initialize / update all git submodules (including nested openvpn3-core).
# Run this once after cloning, and again after pulling changes that bump a submodule.
init submodules:
	git submodule update --init --recursive

# OpenSSL and LZ4 are built from source automatically by CMake
# (ExternalProject_Add in app/src/main/cpp/CMakeLists.txt).
# No manual pre-build step needed.
deps:
	@echo "deps are built automatically by CMake during the first Gradle build."

# Release signing via `pass`.
#
# pass store layout:
#   openlawsvpn/android/keystore-base64   — base64-encoded .keystore file
#   openlawsvpn/android/keystore-password — keystore/key password (PKCS12: one password for both)
#
# PKCS12 keystores use a single password for the store and every key entry.
# KEY_PASSWORD must equal KEYSTORE_PASSWORD — both are set from keystore-password.
#
# The keystore is decoded into a mktemp file for the duration of the build,
# then deleted (trap ensures cleanup even on failure).
#
PASS_PREFIX ?= openlawsvpn/android
KEY_ALIAS   ?= openlawsvpn

# Build a signed release APK (sideload / F-Droid).
release:
	@set -e; \
	ks=$$(mktemp --suffix=.keystore); \
	trap "rm -f $$ks" EXIT; \
	pass show $(PASS_PREFIX)/keystore-base64 | base64 -d > $$ks; \
	ksp="$$(pass show $(PASS_PREFIX)/keystore-password)"; \
	KEYSTORE_PATH="$$ks" \
	KEYSTORE_PASSWORD="$$ksp" \
	KEY_ALIAS="$(KEY_ALIAS)" \
	KEY_PASSWORD="$$ksp" \
	./gradlew assembleRelease
	@echo "APK: app/build/outputs/apk/release/app-release.apk"

# Build a signed release AAB (Google Play).
bundle:
	@set -e; \
	ks=$$(mktemp --suffix=.keystore); \
	trap "rm -f $$ks" EXIT; \
	pass show $(PASS_PREFIX)/keystore-base64 | base64 -d > $$ks; \
	ksp="$$(pass show $(PASS_PREFIX)/keystore-password)"; \
	KEYSTORE_PATH="$$ks" \
	KEYSTORE_PASSWORD="$$ksp" \
	KEY_ALIAS="$(KEY_ALIAS)" \
	KEY_PASSWORD="$$ksp" \
	./gradlew bundleRelease
	@echo "AAB: app/build/outputs/bundle/release/app-release.aab"

lint:
	./gradlew lintDebug

test:
	./gradlew testDebugUnitTest

clean:
	./gradlew clean

# Remove downloaded sources (prebuilt output is kept).
clean-deps-build:
	rm -rf .deps-build/

# Upgrade the Gradle wrapper to a new version.
# Find VERSION and SHA256 at https://gradle.org/releases/
# Usage: make wrapper VERSION=9.4.1 SHA256=2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb
wrapper:
	@test -n "$(VERSION)" || (echo "Usage: make wrapper VERSION=X.Y.Z SHA256=<checksum>"; exit 1)
	@test -n "$(SHA256)"  || (echo "Usage: make wrapper VERSION=X.Y.Z SHA256=<checksum>"; exit 1)
	./gradlew wrapper --gradle-version $(VERSION) --gradle-distribution-sha256-sum $(SHA256)
	@echo "Wrapper updated. Commit gradle/wrapper/ and gradlew* files."
