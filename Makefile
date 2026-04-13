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

# Build a signed release APK (sideload / F-Droid).
#
# Only the two passwords are secrets and come from `pass`.
# KEYSTORE_PATH is a plain file path (not secret) — override on the command line
# or set it in local.properties if you prefer.
# KEY_ALIAS is just the alias name used when the keystore was created.
#
# pass store layout:  openlawsvpn/android/keystore-password
#                     openlawsvpn/android/key-password
#
PASS_PREFIX  ?= openlawsvpn/android
KEYSTORE_PATH ?= $(HOME)/secrets/openlawsvpn-release.keystore
KEY_ALIAS     ?= openlawsvpn

release:
	KEYSTORE_PATH="$(KEYSTORE_PATH)" \
	KEYSTORE_PASSWORD="$$(pass show $(PASS_PREFIX)/keystore-password)" \
	KEY_ALIAS="$(KEY_ALIAS)" \
	KEY_PASSWORD="$$(pass show $(PASS_PREFIX)/key-password)" \
	./gradlew assembleRelease
	@echo "APK: app/build/outputs/apk/release/app-release.apk"

# Build a signed release AAB (Google Play).
bundle:
	KEYSTORE_PATH="$(KEYSTORE_PATH)" \
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
