.PHONY: init submodules clean

# Initialize / update all git submodules (including nested openvpn3-core).
# Run this once after cloning, and again after pulling changes that bump a submodule.
init submodules:
	git submodule update --init --recursive

clean:
	./gradlew clean
