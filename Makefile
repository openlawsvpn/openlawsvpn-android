.PHONY: init submodules deps release bundle lint test clean clean-deps-build

# Initialize / update all git submodules (including nested openvpn3-core).
# Run this once after cloning, and again after pulling changes that bump a submodule.
init submodules:
	git submodule update --init --recursive

# Cross-compile OpenSSL and LZ4 static libs for arm64-v8a and x86_64.
# Requires Android NDK — auto-detected from ~/Android/Sdk/ndk/* or ANDROID_NDK env var.
# Output: prebuilt/{openssl,lz4}/<ABI>/{include,lib}/
deps:
	./scripts/build-deps-android.sh

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
