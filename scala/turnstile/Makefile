.DEFAULT_GOAL := help

help: ## Display this help screen
	@grep -E '^[a-z.A-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

build:  ## Build
	sbt compile package

clean:  ## Clean
	sbt clean

build-clean: clean  ## Clean build
	sbt compile package

docker-publish: build-clean  ## Build & publish docker image to registry
	sbt docker:publish

#docker-context-dockerdesktop: ## Docker desktop
#	eval $(docker-machine env -u)

#docker-context-minikube: ## Minikube
#	eval $(minikube docker-env)

docker-publish-local: ## Build & publish docker image to local registry
	sbt docker:publishLocal

minikube-delete:
	kubectl delete -f minikubeConfig/akka-cluster.yml

minikube-apply:
	kubectl apply -f minikubeConfig/akka-cluster.yml

minikube-deploy: ## Deploy/redeploy on minikube 
	@echo "-- Ensure minikube is set-up --"
	@echo "-- Ensure terminal env points to minikube --" # Run : eval $(minikube docker-env)
	$(MAKE) docker-publish-local
	$(MAKE) minikube-delete
	$(MAKE) minikube-apply
