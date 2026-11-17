# One-command AAR build for the Android car head-unit port.
# Run from this directory:  make aar
# Verify the artifact:      make check
# Print toolchain info:     make info

SHELL := /usr/bin/env bash

.PHONY: all aar check info clean toolchain

all: aar check

aar:
	bash ./build.sh

check:
	bash ./check.sh

info:
	@bash -c 'source ./env.sh; echo "GOROOT=$GOROOT"; echo "GOPATH=$GOPATH"; echo "ANDROID_HOME=$ANDROID_HOME"; echo "JAVA_HOME=$JAVA_HOME"; echo "TARGET=$TARGET"; echo "ANDROID_API=$ANDROID_API"; echo "AAR_OUT=$AAR_OUT"; check_toolchain && echo "TOOLCHAIN_OK"'

clean:
	rm -rf "$(CURDIR)/../build/mygobridge.aar" "$(CURDIR)/../build/.aarcheck"

toolchain:
	@bash -c 'source ./env.sh; check_toolchain || exit 1; echo "Go: $$($GOROOT/bin/go.exe version)"; echo "javac: $$($JAVA_HOME/bin/javac.exe -version 2>&1)"'
