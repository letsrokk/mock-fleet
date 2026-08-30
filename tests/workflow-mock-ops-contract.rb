#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

def workflow(path)
  YAML.safe_load(File.read(path), aliases: true)
end

def fail_contract(message)
  warn(message)
  exit(1)
end

build = workflow(".github/workflows/build.yml")
mock_ops_job = build.fetch("jobs", {}).fetch("fleet-mock-ops", nil)
fail_contract("Build workflow does not package fleet-mock-ops") unless mock_ops_job
helm_commands = build.dig("jobs", "helm", "steps").map { |step| step.fetch("run", "") }.join("\n")
unless helm_commands.include?("deploy/helm/mock-fleet/tests/render-mock-ops.sh") &&
    helm_commands.include?("ruby tests/workflow-mock-ops-contract.rb")
  fail_contract("Build workflow does not run the Mock Ops Helm and workflow contracts")
end
build_steps = mock_ops_job.fetch("steps", []).map { |step| step.fetch("run", "") }.join("\n")
fail_contract("Mock Ops build does not run its Maven wrapper") unless build_steps.include?("./mvnw -B package")
build_artifacts = mock_ops_job.fetch("steps", []).select { |step| step["uses"] == "actions/upload-artifact@v4" }
unless build_artifacts.any? { |step| step.dig("with", "name") == "fleet-mock-ops-package" && step.dig("with", "path") == "fleet-mock-ops/target/quarkus-app" }
  fail_contract("Mock Ops package artifact is missing")
end

publish = workflow(".github/workflows/publish.yml")
fail_contract("Publish workflow has no Mock Ops image name") unless
  publish.dig("env", "MOCK_OPS_IMAGE_NAME") == "ghcr.io/letsrokk/mock-fleet/mock-ops"
publish_steps = publish.dig("jobs", "publish", "steps") || []
unless publish_steps.any? { |step| step.dig("with", "name") == "fleet-mock-ops-package" && step.dig("with", "path") == "fleet-mock-ops/target/quarkus-app" }
  fail_contract("Publish workflow does not download the Mock Ops package")
end
mock_ops_metadata_step = publish_steps.find { |step| step["name"] == "Extract Mock Ops Docker metadata" }
unless mock_ops_metadata_step&.dig("with", "annotations")&.include?("mock-fleet-mock-ops")
  fail_contract("Mock Ops image annotations identify the wrong component")
end
mock_ops_image_step = publish_steps.find { |step| step["name"] == "Build and push Mock Ops Docker image" }
unless mock_ops_image_step&.dig("with", "context") == "fleet-mock-ops" &&
    mock_ops_image_step&.dig("with", "file") == "fleet-mock-ops/src/main/docker/Dockerfile.jvm" &&
    mock_ops_image_step&.dig("with", "push") == true
  fail_contract("Publish workflow does not push the Mock Ops image")
end

cluster = workflow(".github/workflows/cluster-e2e.yml")
cluster_steps = cluster.dig("jobs", "cluster-e2e", "steps") || []
cluster_commands = cluster_steps.map { |step| step.fetch("run", "") }.join("\n")
fail_contract("Cluster workflow does not package Mock Ops") unless cluster_commands.include?("fleet-mock-ops/mvnw")
unless cluster_commands.include?("fleet-mock-ops/src/main/docker/Dockerfile.jvm") &&
    cluster_commands.include?("mock-fleet/mock-ops:${MOCK_FLEET_E2E_IMAGE_TAG}")
  fail_contract("Cluster workflow does not build the Mock Ops image inside Minikube")
end

puts "Mock Ops workflow contract passed."
