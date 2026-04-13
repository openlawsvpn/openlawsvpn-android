.PHONY: init submodules deps release bundle clean clean-deps-build

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
#   openlawsvpn/android/keystore-password — keystore password
#   openlawsvpn/android/key-password      — key password
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
	KEYSTORE_PATH="$$ks" \
	KEYSTORE_PASSWORD="$$(pass show $(PASS_PREFIX)/keystore-password)" \
	KEY_ALIAS="$(KEY_ALIAS)" \
	KEY_PASSWORD="$$(pass show $(PASS_PREFIX)/key-password)" \
	./gradlew assembleRelease
	@echo "APK: app/build/outputs/apk/release/app-release.apk"

# Build a signed release AAB (Google Play).
bundle:
	@set -e; \
	ks=$$(mktemp --suffix=.keystore); \
	trap "rm -f $$ks" EXIT; \
	pass show $(PASS_PREFIX)/keystore-base64 | base64 -d > $$ks; \
	KEYSTORE_PATH="$$ks" \
	KEYSTORE_PASSWORD="$$(pass show $(PASS_PREFIX)/keystore-password)" \
	KEY_ALIAS="$(KEY_ALIAS)" \
	KEY_PASSWORD="$$(pass show $(PASS_PREFIX)/key-password)" \
	./gradlew bundleRelease
	@echo "AAB: app/build/outputs/bundle/release/app-release.aab"

clean:
	./gradlew clean

# Remove downloaded sources (prebuilt output is kept).
clean-deps-build:
	rm -rf .deps-build/
