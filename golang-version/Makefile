GO ?= go
BINARY ?= bin/tcm-server

.PHONY: test vet build run clean

test:
	$(GO) test ./...

vet:
	$(GO) vet ./...

build:
	mkdir -p bin
	$(GO) build -trimpath -o $(BINARY) ./cmd/tcm-server

run:
	$(GO) run ./cmd/tcm-server

clean:
	rm -rf bin
