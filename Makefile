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
# Reads signing credentials from `pass` — configure PASS_PREFIX to match your store layout.
# Example tree: openlawsvpn/android/{keystore-path,keystore-password,key-alias,key-password}
PASS_PREFIX ?= openlawsvpn/android
release:
	KEYSTORE_PATH="$$(pass show $(PASS_PREFIX)/keystore-path)" \
	KEYSTORE_PASSWORD="$$(pass show $(PASS_PREFIX)/keystore-password)" \
	KEY_ALIAS="$$(pass show $(PASS_PREFIX)/key-alias)" \
	KEY_PASSWORD="$$(pass show $(PASS_PREFIX)/key-password)" \
	./gradlew assembleRelease
	@echo "APK: app/build/outputs/apk/release/app-release.apk"

# Build a signed release AAB (Google Play).
bundle:
	KEYSTORE_PATH="$$(pass show $(PASS_PREFIX)/keystore-path)" \
	KEYSTORE_PASSWORD="$$(pass show $(PASS_PREFIX)/keystore-password)" \
	KEY_ALIAS="$$(pass show $(PASS_PREFIX)/key-alias)" \
	KEY_PASSWORD="$$(pass show $(PASS_PREFIX)/key-password)" \
	./gradlew bundleRelease
	@echo "AAB: app/build/outputs/bundle/release/app-release.aab"

clean:
	./gradlew clean

# Remove downloaded sources (prebuilt output is kept).
clean-deps-build:
	rm -rf .deps-build/
