NAMESPACE ?= mock-fleet
RELEASE ?= mock-fleet
LOGS ?= false
DEV ?= false
PORT_FORWARD ?= false
REBUILD ?= false
DELETE_NAMESPACE ?= false

.DEFAULT_GOAL := help

.PHONY: help local-deploy local-destroy

is-one-of = $(and $(filter 1,$(words $(strip $(1)))),$(filter $(strip $(1)),$(2)))
shell-quote = '$(subst ','"'"',$(1))'

help:
	@echo "Local mock-fleet lifecycle targets:"
	@echo "  make local-deploy [NAMESPACE=name] [LOGS=true] [DEV=true|api|proxy] [PORT_FORWARD=true] [REBUILD=dash|api|proxy|mcp|all]"
	@echo "  make local-destroy [NAMESPACE=name] [RELEASE=name] [DELETE_NAMESPACE=true]"

local-deploy:
	$(if $(call is-one-of,$(LOGS),true false),,$(error LOGS must be true or false))
	$(if $(call is-one-of,$(PORT_FORWARD),true false),,$(error PORT_FORWARD must be true or false))
	$(if $(call is-one-of,$(DEV),false true api proxy),,$(error DEV must be false, true, api, or proxy))
	$(if $(call is-one-of,$(REBUILD),false dash api proxy mcp all),,$(error REBUILD must be false, dash, api, proxy, mcp, or all))
	@bin/local/deploy.sh --namespace $(call shell-quote,$(NAMESPACE))$(if $(filter true,$(LOGS)), --logs)$(if $(filter true,$(DEV)), --remote-dev api,$(if $(filter api proxy,$(DEV)), --remote-dev $(DEV)))$(if $(filter true,$(PORT_FORWARD)), --port-forward)$(if $(filter-out false,$(REBUILD)), --rebuild $(call shell-quote,$(REBUILD)))

local-destroy:
	$(if $(call is-one-of,$(DELETE_NAMESPACE),true false),,$(error DELETE_NAMESPACE must be true or false))
	@bin/local/destroy.sh --namespace $(call shell-quote,$(NAMESPACE)) --release $(call shell-quote,$(RELEASE))$(if $(filter true,$(DELETE_NAMESPACE)), --delete-namespace)
