GO ?= go
BINARY ?= bin/tcm-server

.PHONY: test test-race vet check-functions quality build run clean

test:
	$(GO) test ./...

test-race:
	$(GO) test -race ./...

vet:
	$(GO) vet ./...

check-functions:
	$(GO) run ./tools/checkfunc .

quality: test-race vet check-functions

build:
	mkdir -p bin
	$(GO) build -trimpath -o $(BINARY) ./cmd/tcm-server

run:
	$(GO) run ./cmd/tcm-server

clean:
	rm -rf bin
