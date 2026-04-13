.PHONY: init submodules deps clean

# Initialize / update all git submodules (including nested openvpn3-core).
# Run this once after cloning, and again after pulling changes that bump a submodule.
init submodules:
	git submodule update --init --recursive

# Cross-compile OpenSSL and LZ4 static libs for arm64-v8a and x86_64.
# Requires Android NDK — auto-detected from ~/Android/Sdk/ndk/* or ANDROID_NDK env var.
# Output: prebuilt/{openssl,lz4}/<ABI>/{include,lib}/
deps:
	./scripts/build-deps-android.sh

clean:
	./gradlew clean

# Remove downloaded sources (prebuilt output is kept).
clean-deps-build:
	rm -rf .deps-build/
