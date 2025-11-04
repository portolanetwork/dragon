.DEFAULT_GOAL := help

.PHONY: help clean build build-clean docker-publish docker-publish-local


help: ## Display this help screen
	@grep -E '^[a-z.A-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

clean:  ## Clean
	sbt clean

build:  ## Build
	sbt compile package

build-clean: clean  ## Clean build
	sbt compile package

docker-publish: build-clean  ## Build & publish docker image to registry
	sbt docker:publish

docker-publish-local: ## Build & publish docker image to local registry
	sbt docker:publishLocal
