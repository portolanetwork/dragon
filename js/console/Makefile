.DEFAULT_GOAL := help


help: ## Display this help screen
	@echo '-- go =='
	@grep -E '^[a-z.A-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

PROTOC_DIR := ../../resources/proto/
PROTOC_SRC := $(wildcard $(PROTOC_DIR)/**/**/*.proto)

protoc-js: $(PROTOC_SRC)  ## regenerate go proto/grpc stubs
	@echo  'Processing: "$(PROTOC_SRC)'
	npx protoc --ts_out=./src/proto --proto_path=../../resources/proto dragon/turnstile/v1/turnstile_service.proto

build: protoc-js  ## build all go codebase
	npm run build

